"""Application service used by the Day 3 scan MCP tools."""

from __future__ import annotations

import hashlib
import json
import uuid
from datetime import UTC, datetime
from typing import Any

from app.mcp.contracts import McpCallContext
from app.services.mcp_scan_runtime.queue import McpSecurityScanQueue
from app.services.mcp_scan_runtime.store import (
    McpScanError,
    McpScanJobStore,
    RedisMcpScanJobStore,
    decode_cursor,
    encode_cursor,
)
from app.services.project_workspace import (
    DatabaseProjectWorkspaceResolver,
    ResolvedProjectWorkspace,
    ensure_path_within_root,
    validate_identifier,
)

ALLOWED_SCANNERS = frozenset({"semgrep", "gitleaks", "trufflehog", "osv"})
ALLOWED_SEVERITIES = frozenset({"critical", "high", "medium", "low"})


def _now() -> str:
    return datetime.now(UTC).isoformat()


class McpScanService:
    def __init__(
        self,
        *,
        resolver: Any | None = None,
        store: McpScanJobStore | None = None,
        queue: Any | None = None,
    ) -> None:
        self._resolver = resolver or DatabaseProjectWorkspaceResolver()
        self._store = store or RedisMcpScanJobStore()
        self._queue = queue or McpSecurityScanQueue()

    async def start_scan(
        self,
        context: McpCallContext,
        *,
        scanners: list[str],
        target_paths: list[str] | None,
        timeout_seconds: int,
    ) -> tuple[dict[str, Any], list[str]]:
        normalized_scanners = self._validate_scanners(scanners)
        normalized_timeout = int(timeout_seconds)
        if not 30 <= normalized_timeout <= 1800:
            raise McpScanError(
                "INVALID_ARGUMENT",
                "timeout_seconds must be between 30 and 1800",
                details={"field": "timeout_seconds"},
            )

        workspace: ResolvedProjectWorkspace = await self._resolver.resolve(context.project_id)
        normalized_targets = self._validate_targets(workspace, target_paths or ["."])
        request_payload = {
            "project_id": context.project_id,
            "audit_id": context.audit_id,
            "scanners": normalized_scanners,
            "target_paths": normalized_targets,
            "timeout_seconds": normalized_timeout,
        }
        fingerprint = hashlib.sha256(
            json.dumps(request_payload, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        created_at = _now()
        candidate = {
            "job_id": str(uuid.uuid4()),
            "request_id": context.request_id,
            "trace_id": context.trace_id,
            "audit_id": context.audit_id,
            "project_id": context.project_id,
            "request_fingerprint": fingerprint,
            "status": "queued",
            "scanners": normalized_scanners,
            "target_paths": normalized_targets,
            "timeout_seconds": normalized_timeout,
            "created_at": created_at,
            "updated_at": created_at,
            "started_at": None,
            "completed_at": None,
            "findings_count": 0,
            "scanner_runs": [],
            "error": None,
        }
        job, created = await self._store.create_job(candidate)
        if created:
            try:
                await self._queue.enqueue(str(job["job_id"]))
            except McpScanError as exc:
                await self._store.update_job(
                    str(job["job_id"]),
                    status="failed",
                    updated_at=_now(),
                    completed_at=_now(),
                    error={
                        "code": exc.code,
                        "message": str(exc),
                        "retryable": exc.retryable,
                    },
                )
                raise

        data = self._public_job(job)
        data["deduplicated"] = not created
        warnings = ["request_id reused; existing scan job returned"] if not created else []
        return data, warnings

    async def get_job(
        self,
        context: McpCallContext,
        *,
        job_id: str,
    ) -> tuple[dict[str, Any], list[str]]:
        job = await self._owned_job(context, job_id)
        return self._public_job(job), []

    async def get_findings(
        self,
        context: McpCallContext,
        *,
        job_id: str,
        cursor: str | None,
        limit: int,
        severity_filter: list[str] | None,
    ) -> tuple[dict[str, Any], list[str]]:
        normalized_job_id = validate_identifier(job_id, "job_id")
        job = await self._owned_job(context, normalized_job_id)
        normalized_limit = int(limit)
        if not 1 <= normalized_limit <= 100:
            raise McpScanError(
                "INVALID_ARGUMENT",
                "limit must be between 1 and 100",
                details={"field": "limit"},
            )
        severities = [str(item).lower() for item in (severity_filter or [])]
        if len(severities) != len(set(severities)) or any(
            severity not in ALLOWED_SEVERITIES for severity in severities
        ):
            raise McpScanError(
                "INVALID_ARGUMENT",
                "severity_filter contains an unsupported or duplicate value",
                details={"field": "severity_filter"},
            )

        findings = await self._store.list_findings(normalized_job_id)
        if severities:
            findings = [
                finding
                for finding in findings
                if str(finding.get("severity") or "").lower() in severities
            ]
        offset = decode_cursor(cursor, normalized_job_id)
        page = findings[offset : offset + normalized_limit]
        next_offset = offset + len(page)
        next_cursor = (
            encode_cursor(normalized_job_id, next_offset)
            if next_offset < len(findings)
            else None
        )
        warnings = []
        if str(job.get("status")) in {"queued", "running"}:
            warnings.append("scan is still running; findings may be incomplete")
        return {
            "job_id": normalized_job_id,
            "status": job.get("status"),
            "items": page,
            "page_size": len(page),
            "total_matching": len(findings),
            "next_cursor": next_cursor,
        }, warnings

    async def _owned_job(self, context: McpCallContext, job_id: str) -> dict[str, Any]:
        normalized_job_id = validate_identifier(job_id, "job_id")
        job = await self._store.get_job(normalized_job_id)
        if job is None:
            raise McpScanError(
                "INVALID_ARGUMENT",
                "scan job was not found or has expired",
                details={"job_id": normalized_job_id},
            )
        if (
            job.get("project_id") != context.project_id
            or job.get("audit_id") != context.audit_id
        ):
            raise McpScanError(
                "PERMISSION_DENIED",
                "scan job does not belong to the requested project and audit",
                details={"job_id": normalized_job_id},
            )
        return job

    @staticmethod
    def _validate_scanners(scanners: list[str]) -> list[str]:
        normalized = [str(item).strip().lower() for item in scanners]
        if not normalized or len(normalized) != len(set(normalized)):
            raise McpScanError(
                "INVALID_ARGUMENT",
                "scanners must contain unique scanner names",
                details={"field": "scanners"},
            )
        unsupported = sorted(set(normalized) - ALLOWED_SCANNERS)
        if unsupported:
            raise McpScanError(
                "INVALID_ARGUMENT",
                "scanners contains unsupported values",
                details={"unsupported": unsupported},
            )
        return normalized

    @staticmethod
    def _validate_targets(
        workspace: ResolvedProjectWorkspace,
        target_paths: list[str],
    ) -> list[str]:
        if not target_paths or len(target_paths) > 64:
            raise McpScanError(
                "INVALID_ARGUMENT",
                "target_paths must contain between 1 and 64 paths",
                details={"field": "target_paths"},
            )
        normalized: list[str] = []
        for target in target_paths:
            path = ensure_path_within_root(
                workspace.root,
                str(target),
                allow_root=True,
                must_exist=True,
            )
            relative = path.relative_to(workspace.root).as_posix()
            normalized.append(relative if relative != "." else ".")
        return list(dict.fromkeys(normalized))

    @staticmethod
    def _public_job(job: dict[str, Any]) -> dict[str, Any]:
        visible = {
            key: value
            for key, value in job.items()
            if key not in {"request_fingerprint"}
        }
        return visible
