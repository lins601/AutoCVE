"""Execute one approval-bound, predefined verification inside the Docker sandbox."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from pathlib import Path
from typing import Any, Protocol

from app.mcp.contracts import McpCallContext
from app.services.agent.tools.sandbox_tool import SandboxManager
from app.services.project_workspace import (
    DatabaseProjectWorkspaceResolver,
    ResolvedProjectWorkspace,
    validate_identifier,
)

ALLOWED_CATEGORIES = {"authorization", "injection", "logic", "secret", "dependency", "other"}
DEFAULT_TOKEN_SECRET = "autocve-day5-development-secret-change-me"


class McpVerificationError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.details = details


class WorkspaceResolver(Protocol):
    async def resolve(self, project_id: str) -> ResolvedProjectWorkspace: ...


class SandboxExecutor(Protocol):
    async def verify(
        self,
        *,
        workspace: ResolvedProjectWorkspace,
        finding: dict[str, Any],
        timeout_seconds: int,
    ) -> dict[str, Any]: ...


class ApprovalReplayGuard(Protocol):
    async def claim(
        self,
        *,
        approval_id: str,
        action_digest: str,
        expires_at: int,
    ) -> None: ...


class InMemoryApprovalReplayGuard:
    """Process-local guard used when Redis is not configured."""

    def __init__(self, clock: Any = time.time) -> None:
        self._clock = clock
        self._claims: dict[str, int] = {}
        self._lock = asyncio.Lock()

    async def claim(
        self,
        *,
        approval_id: str,
        action_digest: str,
        expires_at: int,
    ) -> None:
        key = f"{approval_id}:{action_digest}"
        now = int(self._clock())
        async with self._lock:
            self._claims = {
                stored: expiry for stored, expiry in self._claims.items() if expiry >= now
            }
            if key in self._claims:
                raise McpVerificationError(
                    "CONFLICT",
                    "approval was already consumed by a verification attempt",
                )
            self._claims[key] = max(now + 1, expires_at)


class RedisApprovalReplayGuard:
    """Durable atomic replay guard shared by all MCP server replicas."""

    def __init__(self, redis_url: str, clock: Any = time.time) -> None:
        from redis.asyncio import from_url

        self._redis = from_url(redis_url, encoding="utf-8", decode_responses=True)
        self._clock = clock

    async def claim(
        self,
        *,
        approval_id: str,
        action_digest: str,
        expires_at: int,
    ) -> None:
        fingerprint = hashlib.sha256(f"{approval_id}:{action_digest}".encode()).hexdigest()
        ttl = max(1, expires_at - int(self._clock()))
        accepted = await self._redis.set(
            f"autocve:mcp:verification-approval:{fingerprint}",
            "consumed",
            ex=ttl,
            nx=True,
        )
        if not accepted:
            raise McpVerificationError(
                "CONFLICT",
                "approval was already consumed by a verification attempt",
            )


def default_replay_guard() -> ApprovalReplayGuard:
    redis_url = os.environ.get("REDIS_URL", "").strip()
    if redis_url:
        return RedisApprovalReplayGuard(redis_url)
    return InMemoryApprovalReplayGuard()


def _digest_field(name: str, value: Any) -> bytes:
    text = "" if value is None else str(value)
    encoded = text.encode("utf-8")
    return f"{name}:{len(encoded)}:".encode() + encoded + b"\n"


def compute_action_digest(
    *,
    audit_id: str,
    project_id: str,
    finding: dict[str, Any],
) -> str:
    """Canonical length-prefixed digest shared with the Java control plane."""
    payload = bytearray()
    fields = (
        ("version", "v1"),
        ("action_type", "verify_vulnerability"),
        ("verification_mode", "sandbox"),
        ("audit_id", audit_id),
        ("project_id", project_id),
        ("finding_id", finding.get("finding_id")),
        ("title", finding.get("title")),
        ("category", finding.get("category")),
        ("file_path", finding.get("file_path")),
        ("line_start", finding.get("line_start")),
    )
    for name, value in fields:
        payload.extend(_digest_field(name, value))
    evidence = finding.get("evidence") if isinstance(finding.get("evidence"), list) else []
    payload.extend(_digest_field("evidence_count", len(evidence)))
    for index, value in enumerate(evidence):
        payload.extend(_digest_field(f"evidence.{index}", value))
    return hashlib.sha256(payload).hexdigest()


class ApprovalTokenVerifier:
    def __init__(self, secret: str | None = None, clock: Any = time.time) -> None:
        self._secret = (
            secret or os.environ.get("MCP_APPROVAL_HMAC_SECRET") or DEFAULT_TOKEN_SECRET
        ).encode()
        self._clock = clock
        if len(self._secret) < 32:
            raise ValueError("MCP approval HMAC secret must contain at least 32 bytes")

    def verify(self, token: str, expected: dict[str, str]) -> dict[str, Any]:
        try:
            payload_segment, signature_segment = token.split(".", 1)
            payload_bytes = _decode_url(payload_segment)
            signature = _decode_url(signature_segment)
            expected_signature = hmac.new(self._secret, payload_bytes, hashlib.sha256).digest()
            if not hmac.compare_digest(signature, expected_signature):
                raise ValueError("signature mismatch")
            claims = json.loads(payload_bytes)
            if not isinstance(claims, dict) or claims.get("version") != "v1":
                raise ValueError("unsupported token")
            if int(claims.get("expires_at") or 0) <= int(self._clock()):
                raise ValueError("token expired")
            for key, value in expected.items():
                if str(claims.get(key) or "") != str(value):
                    raise ValueError(f"claim mismatch: {key}")
            return claims
        except (TypeError, ValueError, json.JSONDecodeError) as exc:
            raise McpVerificationError(
                "PERMISSION_DENIED",
                "approval token is invalid, expired, or not bound to this action",
            ) from exc


def _decode_url(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


class VerificationArtifactStore:
    def __init__(self, root: str | Path | None = None) -> None:
        self._root = Path(
            root or os.environ.get("AUTOCVE_MCP_ARTIFACT_ROOT", "/tmp/autocve-mcp-artifacts")
        )

    def store(self, context: McpCallContext, payload: dict[str, Any]) -> dict[str, str]:
        artifact_id = f"verification-{uuid.uuid4()}"
        directory = self._root / context.audit_id
        directory.mkdir(parents=True, exist_ok=True)
        destination = directory / f"{artifact_id}.json"
        temporary = directory / f".{artifact_id}.tmp"
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, default=str),
            encoding="utf-8",
        )
        temporary.replace(destination)
        return {
            "artifact_id": artifact_id,
            "media_type": "application/json",
            "summary": "Approval-bound sandbox verification evidence",
        }


_SANDBOX_SCRIPT = r"""
import hashlib
import json
import os
import re
import sys
from pathlib import Path

root = Path("/workspace").resolve()
relative = os.environ["AUTOCVE_VERIFY_FILE"]
category = os.environ["AUTOCVE_VERIFY_CATEGORY"]
line_start = max(1, int(os.environ["AUTOCVE_VERIFY_LINE"]))
candidate = (root / relative).resolve()
try:
    candidate.relative_to(root)
except ValueError:
    raise SystemExit("target escaped the read-only workspace")

if not candidate.is_file():
    print(json.dumps({"verdict": "inconclusive", "evidence": ["target file is missing"], "matched_markers": [], "line_window_sha256": None}))
    raise SystemExit(0)

content = candidate.read_text(encoding="utf-8", errors="replace")
lines = content.splitlines()
start = max(0, line_start - 3)
end = min(len(lines), line_start + 2)
window = "\n".join(lines[start:end])
patterns = {
    "secret": [("credential_assignment", r"(?i)(api[_-]?key|secret|password|token)\s*[:=]"), ("high_entropy_token", r"[A-Za-z0-9_\-]{24,}")],
    "injection": [("command_sink", r"(?i)(exec\s*\(|system\s*\(|subprocess\.|Runtime\.getRuntime)"), ("sql_sink", r"(?i)(execute\s*\(|query\s*\(|SELECT\s+.+\+)")],
    "authorization": [("access_control", r"(?i)(authorize|permission|role|is_admin|access[_-]?control)")],
    "logic": [("state_transition", r"(?i)(status|state|transition|approve|balance)")],
    "dependency": [("dependency_declaration", r"(?i)(dependencies|requirements|artifactId|package-lock|version)")],
    "other": [("security_marker", r"(?i)(vulnerab|unsafe|security|sanitize|validate)")],
}
matched = [name for name, pattern in patterns.get(category, patterns["other"]) if re.search(pattern, window)]
verdict = "likely" if matched else "inconclusive"
evidence = [f"matched predefined marker: {name}" for name in matched]
if not evidence:
    evidence = ["no predefined marker matched the bounded source window"]
print(json.dumps({
    "verdict": verdict,
    "evidence": evidence,
    "matched_markers": matched,
    "line_window_sha256": hashlib.sha256(window.encode()).hexdigest(),
    "source_sha256": hashlib.sha256(content.encode()).hexdigest(),
    "line_count": len(lines),
    "python": sys.version.split()[0],
}, separators=(",", ":")))
"""
_SANDBOX_COMMAND = (
    "python3 -c \"import base64;exec(base64.b64decode('"
    + base64.b64encode(_SANDBOX_SCRIPT.encode()).decode()
    + "'))\""
)


class VerificationSandboxExecutor:
    def __init__(self, manager: SandboxManager | None = None) -> None:
        self._manager = manager or SandboxManager()

    async def verify(
        self,
        *,
        workspace: ResolvedProjectWorkspace,
        finding: dict[str, Any],
        timeout_seconds: int,
    ) -> dict[str, Any]:
        await self._manager.initialize()
        if not self._manager.is_available:
            raise McpVerificationError(
                "SANDBOX_ERROR",
                "Docker sandbox is unavailable",
                retryable=True,
            )
        result = await self._manager.execute_tool_command(
            _SANDBOX_COMMAND,
            str(workspace.root),
            timeout=timeout_seconds,
            env={
                "AUTOCVE_VERIFY_FILE": str(finding["file_path"]),
                "AUTOCVE_VERIFY_CATEGORY": str(finding["category"]),
                "AUTOCVE_VERIFY_LINE": str(finding["line_start"]),
            },
            network_mode="none",
        )
        error = str(result.get("error") or "")
        timed_out = "超时" in error or "timeout" in error.lower()
        parsed: dict[str, Any] = {}
        if result.get("stdout"):
            try:
                parsed = json.loads(str(result["stdout"]).strip().splitlines()[-1])
            except (IndexError, json.JSONDecodeError):
                parsed = {}
        return {
            "verdict": parsed.get("verdict") if parsed else "inconclusive",
            "evidence": (
                parsed.get("evidence")
                if isinstance(parsed.get("evidence"), list)
                else [error or "sandbox returned no structured evidence"]
            ),
            "matched_markers": parsed.get("matched_markers") or [],
            "line_window_sha256": parsed.get("line_window_sha256"),
            "source_sha256": parsed.get("source_sha256"),
            "line_count": parsed.get("line_count"),
            "execution": {
                "success": bool(result.get("success")),
                "exit_code": int(result.get("exit_code", -1)),
                "timed_out": timed_out,
                "stderr": str(result.get("stderr") or "")[:2000],
                "error": error[:2000] or None,
                "environment": {
                    "runtime": f"python-{parsed.get('python', 'unknown')}",
                    "network": "none",
                    "workspace": "read-only",
                },
            },
        }


class McpVerificationService:
    def __init__(
        self,
        *,
        resolver: WorkspaceResolver | None = None,
        token_verifier: ApprovalTokenVerifier | None = None,
        replay_guard: ApprovalReplayGuard | None = None,
        executor: SandboxExecutor | None = None,
        artifacts: VerificationArtifactStore | None = None,
    ) -> None:
        self._resolver = resolver or DatabaseProjectWorkspaceResolver()
        self._tokens = token_verifier or ApprovalTokenVerifier()
        self._replay_guard = replay_guard or default_replay_guard()
        self._executor = executor or VerificationSandboxExecutor()
        self._artifacts = artifacts or VerificationArtifactStore()

    async def verify(
        self,
        context: McpCallContext,
        *,
        approval_id: str,
        approval_token: str,
        action_digest: str,
        finding: dict[str, Any],
        verification_mode: str,
        timeout_seconds: int,
    ) -> tuple[dict[str, Any], list[str], list[dict[str, str]]]:
        approval_id = validate_identifier(approval_id, "approval_id")
        if verification_mode != "sandbox":
            raise McpVerificationError("INVALID_ARGUMENT", "verification_mode must be sandbox")
        if not 10 <= timeout_seconds <= 300:
            raise McpVerificationError(
                "INVALID_ARGUMENT", "timeout_seconds must be between 10 and 300"
            )
        normalized = self._validate_finding(finding)
        calculated = compute_action_digest(
            audit_id=context.audit_id,
            project_id=context.project_id,
            finding=normalized,
        )
        if not hmac.compare_digest(calculated, str(action_digest or "")):
            raise McpVerificationError(
                "PERMISSION_DENIED",
                "action digest does not match the approved finding",
            )
        claims = self._tokens.verify(
            approval_token,
            {
                "approval_id": approval_id,
                "audit_id": context.audit_id,
                "project_id": context.project_id,
                "finding_id": normalized["finding_id"],
                "action_digest": calculated,
            },
        )
        await self._replay_guard.claim(
            approval_id=approval_id,
            action_digest=calculated,
            expires_at=int(claims["expires_at"]),
        )
        workspace = await self._resolver.resolve(context.project_id)
        self._require_safe_file(workspace, normalized["file_path"])
        result = await self._executor.verify(
            workspace=workspace,
            finding=normalized,
            timeout_seconds=timeout_seconds,
        )
        artifact_payload = {
            "approval_id": approval_id,
            "audit_id": context.audit_id,
            "project_id": context.project_id,
            "finding_id": normalized["finding_id"],
            "action_digest": calculated,
            "verification_mode": verification_mode,
            **result,
        }
        artifact = self._artifacts.store(context, artifact_payload)
        data = {
            "approval_id": approval_id,
            "finding_id": normalized["finding_id"],
            "action_digest": calculated,
            "verdict": result["verdict"],
            "evidence": result["evidence"],
            "execution": result["execution"],
            "artifact_id": artifact["artifact_id"],
        }
        warnings = [
            "Verification used fixed category-specific checks; no model-generated command was executed."
        ]
        return data, warnings, [artifact]

    @staticmethod
    def _validate_finding(finding: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(finding, dict):
            raise McpVerificationError("INVALID_ARGUMENT", "finding must be an object")
        allowed_fields = {"finding_id", "title", "category", "file_path", "line_start", "evidence"}
        if set(finding) - allowed_fields:
            raise McpVerificationError(
                "INVALID_ARGUMENT", "finding contains fields outside the canonical contract"
            )
        title = str(finding.get("title") or "")
        file_path = str(finding.get("file_path") or "")
        raw_evidence = finding.get("evidence")
        if not isinstance(raw_evidence, list):
            raise McpVerificationError("INVALID_ARGUMENT", "finding evidence must be an array")
        evidence = [str(value) for value in raw_evidence]
        try:
            line_start = int(finding.get("line_start") or 0)
        except (TypeError, ValueError) as exc:
            raise McpVerificationError(
                "INVALID_ARGUMENT", "finding line_start must be an integer"
            ) from exc
        normalized = {
            "finding_id": validate_identifier(str(finding.get("finding_id") or ""), "finding_id"),
            "title": title,
            "category": str(finding.get("category") or ""),
            "file_path": file_path,
            "line_start": line_start,
            "evidence": evidence,
        }
        if not title.strip() or len(title) > 500 or not file_path.strip() or len(file_path) > 4096:
            raise McpVerificationError(
                "INVALID_ARGUMENT", "finding title and file_path are required"
            )
        if normalized["category"] not in ALLOWED_CATEGORIES:
            raise McpVerificationError("INVALID_ARGUMENT", "finding category is unsupported")
        if (
            line_start < 1
            or not evidence
            or len(evidence) > 20
            or any(not value.strip() or len(value) > 4000 for value in evidence)
        ):
            raise McpVerificationError(
                "INVALID_ARGUMENT", "finding line_start and evidence are required"
            )
        return normalized

    @staticmethod
    def _require_safe_file(workspace: ResolvedProjectWorkspace, relative_path: str) -> None:
        candidate = (workspace.root / relative_path).resolve()
        try:
            candidate.relative_to(workspace.root.resolve())
        except ValueError as exc:
            raise McpVerificationError(
                "PERMISSION_DENIED", "finding path escapes project workspace"
            ) from exc
        if not candidate.is_file():
            raise McpVerificationError("INVALID_ARGUMENT", "finding file does not exist")
