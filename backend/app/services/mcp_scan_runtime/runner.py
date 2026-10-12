"""ARQ worker-side execution of deterministic scanner tools."""

from __future__ import annotations

import asyncio
import hashlib
import logging
from datetime import UTC, datetime
from typing import Any, Protocol

from app.services.agent.tools.external_tools import (
    GitleaksTool,
    OSVScannerTool,
    SemgrepTool,
    TruffleHogTool,
)
from app.services.agent.tools.sandbox_tool import SandboxManager
from app.services.mcp_scan_runtime.store import McpScanJobStore, RedisMcpScanJobStore
from app.services.project_workspace import (
    DatabaseProjectWorkspaceResolver,
    ProjectWorkspaceError,
)

logger = logging.getLogger(__name__)
MAX_STORED_FINDINGS = 2000


def _now() -> str:
    return datetime.now(UTC).isoformat()


class ScannerExecutor(Protocol):
    async def execute(
        self,
        *,
        project_root: str,
        scanners: list[str],
        target_paths: list[str],
        timeout_seconds: int,
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]: ...


class ExistingSecurityToolExecutor:
    """Adapter that reuses AutoCVE's existing sandboxed scanner implementations."""

    def __init__(self, sandbox_manager: SandboxManager | None = None) -> None:
        self._sandbox = sandbox_manager or SandboxManager()

    async def execute(
        self,
        *,
        project_root: str,
        scanners: list[str],
        target_paths: list[str],
        timeout_seconds: int,
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        tools = {
            "semgrep": SemgrepTool(project_root, self._sandbox),
            "gitleaks": GitleaksTool(project_root, self._sandbox),
            "trufflehog": TruffleHogTool(project_root, self._sandbox),
            "osv": OSVScannerTool(project_root, self._sandbox),
        }
        findings: list[dict[str, Any]] = []
        runs: list[dict[str, Any]] = []

        async with asyncio.timeout(timeout_seconds):
            for scanner in scanners:
                for target_path in target_paths:
                    result = await tools[scanner].execute(target_path=target_path)
                    normalized = self._normalize_result(
                        scanner=scanner,
                        target_path=target_path,
                        result=result.to_dict(),
                    )
                    remaining = MAX_STORED_FINDINGS - len(findings)
                    if remaining > 0:
                        findings.extend(normalized[:remaining])
                    runs.append(
                        {
                            "scanner": scanner,
                            "target_path": target_path,
                            "status": "completed" if result.success else "failed",
                            "duration_ms": result.duration_ms,
                            "findings_count": int(
                                (result.metadata or {}).get("findings_count") or len(normalized)
                            ),
                            "indexed_count": len(normalized),
                            "error": str(result.error)[:1000] if result.error else None,
                        }
                    )
        return findings, runs

    @classmethod
    def _normalize_result(
        cls,
        *,
        scanner: str,
        target_path: str,
        result: dict[str, Any],
    ) -> list[dict[str, Any]]:
        metadata = result.get("metadata") if isinstance(result.get("metadata"), dict) else {}
        raw_findings = (
            metadata.get("findings")
            if isinstance(metadata.get("findings"), list)
            else []
        )
        normalized: list[dict[str, Any]] = []

        for raw in raw_findings:
            if not isinstance(raw, dict):
                continue
            if scanner == "semgrep":
                extra = raw.get("extra") if isinstance(raw.get("extra"), dict) else {}
                start = raw.get("start") if isinstance(raw.get("start"), dict) else {}
                rule_id = str(raw.get("check_id") or "semgrep-finding")
                file_path = str(raw.get("path") or target_path).replace("\\", "/")
                line_start = cls._positive_int(start.get("line"), 1)
                severity = cls._semgrep_severity(str(extra.get("severity") or ""))
                title = str(extra.get("message") or rule_id)[:500]
                evidence = str(extra.get("lines") or title)[:2000]
            else:
                rule_id = str(raw.get("rule") or raw.get("RuleID") or f"{scanner}-finding")
                file_path = str(raw.get("file") or raw.get("File") or target_path).replace(
                    "\\", "/"
                )
                line_start = cls._positive_int(raw.get("line") or raw.get("StartLine"), 1)
                severity = "high"
                title = str(raw.get("description") or raw.get("Description") or rule_id)[:500]
                evidence = title[:2000]
            normalized.append(
                cls._finding(
                    scanner=scanner,
                    rule_id=rule_id,
                    severity=severity,
                    file_path=file_path,
                    line_start=line_start,
                    title=title,
                    evidence=evidence,
                )
            )

        reported_count = cls._positive_int(metadata.get("findings_count"), 0)
        if reported_count > 0 and not normalized:
            summary = str(result.get("data") or f"{scanner} reported findings")[:2000]
            normalized.append(
                cls._finding(
                    scanner=scanner,
                    rule_id=f"{scanner}-summary",
                    severity="high" if scanner in {"gitleaks", "trufflehog"} else "medium",
                    file_path=target_path,
                    line_start=1,
                    title=f"{scanner} reported {reported_count} candidate findings",
                    evidence=summary,
                    aggregate_count=reported_count,
                )
            )
        return normalized

    @staticmethod
    def _finding(
        *,
        scanner: str,
        rule_id: str,
        severity: str,
        file_path: str,
        line_start: int,
        title: str,
        evidence: str,
        aggregate_count: int | None = None,
    ) -> dict[str, Any]:
        fingerprint = hashlib.sha256(
            f"{scanner}|{rule_id}|{file_path}|{line_start}|{title}".encode(
                "utf-8", errors="ignore"
            )
        ).hexdigest()
        category = (
            "secret"
            if scanner in {"gitleaks", "trufflehog"}
            else "dependency"
            if scanner == "osv"
            else "other"
        )
        item: dict[str, Any] = {
            "finding_id": fingerprint,
            "scanner": scanner,
            "rule_id": rule_id,
            "category": category,
            "severity": severity,
            "title": title,
            "file_path": file_path,
            "line_start": line_start,
            "evidence": [evidence],
            "status": "candidate",
        }
        if aggregate_count is not None:
            item["aggregate_count"] = aggregate_count
        return item

    @staticmethod
    def _semgrep_severity(value: str) -> str:
        return {"ERROR": "high", "WARNING": "medium", "INFO": "low"}.get(
            value.upper(), "medium"
        )

    @staticmethod
    def _positive_int(value: Any, default: int) -> int:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return default
        return parsed if parsed >= 0 else default


class McpScanJobRunner:
    def __init__(
        self,
        *,
        store: McpScanJobStore | None = None,
        resolver: Any | None = None,
        executor: ScannerExecutor | None = None,
    ) -> None:
        self._store = store or RedisMcpScanJobStore()
        self._resolver = resolver or DatabaseProjectWorkspaceResolver()
        self._executor = executor or ExistingSecurityToolExecutor()

    async def run(self, job_id: str) -> None:
        job = await self._store.get_job(job_id)
        if job is None:
            logger.warning("MCP scan job %s no longer exists", job_id)
            return
        if job.get("status") in {"completed", "failed", "cancelled"}:
            return

        await self._store.update_job(
            job_id,
            status="running",
            started_at=_now(),
            updated_at=_now(),
            error=None,
        )
        try:
            workspace = await self._resolver.resolve(str(job["project_id"]))
            findings, scanner_runs = await self._executor.execute(
                project_root=str(workspace.root),
                scanners=list(job["scanners"]),
                target_paths=list(job["target_paths"]),
                timeout_seconds=int(job["timeout_seconds"]),
            )
            await self._store.replace_findings(job_id, findings)
            successful_runs = sum(run.get("status") == "completed" for run in scanner_runs)
            final_status = "completed" if successful_runs else "failed"
            error = None
            if not successful_runs:
                error = {
                    "code": "SANDBOX_ERROR",
                    "message": "all requested scanners failed",
                    "retryable": True,
                }
            await self._store.update_job(
                job_id,
                status=final_status,
                scanner_runs=scanner_runs,
                findings_count=len(findings),
                completed_at=_now(),
                updated_at=_now(),
                error=error,
            )
        except TimeoutError:
            await self._fail(job_id, "TOOL_TIMEOUT", "scan job exceeded its timeout", True)
        except ProjectWorkspaceError as exc:
            await self._fail(job_id, exc.code, str(exc), exc.retryable)
        except Exception:
            logger.exception("MCP scan job %s failed", job_id)
            await self._fail(job_id, "INTERNAL_ERROR", "scan worker failed", False)

    async def _fail(self, job_id: str, code: str, message: str, retryable: bool) -> None:
        await self._store.update_job(
            job_id,
            status="failed",
            completed_at=_now(),
            updated_at=_now(),
            error={"code": code, "message": message, "retryable": retryable},
        )
