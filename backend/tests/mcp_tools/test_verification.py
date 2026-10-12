from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from pathlib import Path
from typing import Any

import pytest

from app.mcp.responses import McpResponseLimiter
from app.mcp.tools import McpSecurityTools
from app.services.mcp_verification import (
    ApprovalTokenVerifier,
    InMemoryApprovalReplayGuard,
    McpVerificationService,
    VerificationArtifactStore,
    compute_action_digest,
)
from app.services.project_workspace import ResolvedProjectWorkspace

SECRET = "test-day5-approval-secret-that-is-at-least-32-bytes"


class StaticResolver:
    def __init__(self, root: Path) -> None:
        self.root = root

    async def resolve(self, project_id: str) -> ResolvedProjectWorkspace:
        return ResolvedProjectWorkspace(project_id, "fixture", "local_directory", self.root)


class RecordingExecutor:
    def __init__(self, *, timed_out: bool = False) -> None:
        self.calls = 0
        self.timed_out = timed_out

    async def verify(
        self,
        *,
        workspace: ResolvedProjectWorkspace,
        finding: dict[str, Any],
        timeout_seconds: int,
    ) -> dict[str, Any]:
        self.calls += 1
        return {
            "verdict": "inconclusive" if self.timed_out else "likely",
            "evidence": ["sandbox timed out" if self.timed_out else "matched marker"],
            "execution": {
                "success": not self.timed_out,
                "exit_code": -1 if self.timed_out else 0,
                "timed_out": self.timed_out,
                "stderr": "",
                "error": "timeout" if self.timed_out else None,
                "environment": {
                    "runtime": "python-test",
                    "network": "none",
                    "workspace": "read-only",
                },
            },
        }


def _encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def _token(**claims: Any) -> str:
    payload = json.dumps(
        {"version": "v1", **claims},
        sort_keys=True,
        separators=(",", ":"),
    ).encode()
    signature = hmac.new(SECRET.encode(), payload, hashlib.sha256).digest()
    return f"{_encode(payload)}.{_encode(signature)}"


def _finding() -> dict[str, Any]:
    return {
        "finding_id": "finding-1",
        "title": "Possible SQL injection",
        "category": "injection",
        "file_path": "app.py",
        "line_start": 2,
        "evidence": ["query concatenates user input"],
    }


def _tools(tmp_path: Path, executor: RecordingExecutor) -> McpSecurityTools:
    repository = tmp_path / "repository"
    repository.mkdir()
    (repository / "app.py").write_text(
        "def load(value):\n    return db.execute('select ' + value)\n",
        encoding="utf-8",
    )
    resolver = StaticResolver(repository)
    service = McpVerificationService(
        resolver=resolver,
        token_verifier=ApprovalTokenVerifier(SECRET),
        replay_guard=InMemoryApprovalReplayGuard(),
        executor=executor,
        artifacts=VerificationArtifactStore(tmp_path / "artifacts"),
    )
    return McpSecurityTools(
        resolver=resolver,
        response_limiter=McpResponseLimiter(tmp_path / "oversized"),
        verification_service=service,
    )


def _arguments() -> dict[str, Any]:
    finding = _finding()
    digest = compute_action_digest(
        audit_id="audit-1",
        project_id="project-1",
        finding=finding,
    )
    return {
        "request_id": "verify-attempt-1",
        "trace_id": "trace-1",
        "audit_id": "audit-1",
        "project_id": "project-1",
        "approval_id": "approval-1",
        "approval_token": _token(
            approval_id="approval-1",
            audit_id="audit-1",
            project_id="project-1",
            finding_id="finding-1",
            action_digest=digest,
            expires_at=int(time.time()) + 60,
        ),
        "action_digest": digest,
        "finding": finding,
        "verification_mode": "sandbox",
        "timeout_seconds": 30,
    }


def test_action_digest_matches_shared_java_contract_vector() -> None:
    assert (
        compute_action_digest(
            audit_id="audit-1",
            project_id="project-1",
            finding=_finding(),
        )
        == "af73f3df284531eac31640f0324d966009c5e3c9a5f3c48fb1ae9937b3fa0177"
    )


@pytest.mark.asyncio
async def test_signed_approval_runs_only_bound_finding_and_writes_artifact(
    tmp_path: Path,
) -> None:
    executor = RecordingExecutor()
    tools = _tools(tmp_path, executor)

    response = await tools.verify_vulnerability(**_arguments())

    assert response["ok"] is True
    assert response["data"]["finding_id"] == "finding-1"
    assert response["data"]["verdict"] == "likely"
    assert response["data"]["execution"]["environment"]["network"] == "none"
    assert len(response["artifacts"]) == 1
    assert executor.calls == 1
    stored = list((tmp_path / "artifacts" / "audit-1").glob("verification-*.json"))
    assert len(stored) == 1


@pytest.mark.asyncio
async def test_invalid_token_and_changed_finding_never_reach_sandbox(tmp_path: Path) -> None:
    executor = RecordingExecutor()
    tools = _tools(tmp_path, executor)
    arguments = _arguments()
    arguments["finding"] = {**arguments["finding"], "file_path": "changed.py"}

    response = await tools.verify_vulnerability(**arguments)

    assert response["ok"] is False
    assert response["error"]["code"] == "PERMISSION_DENIED"
    assert executor.calls == 0


@pytest.mark.asyncio
async def test_invalid_signature_never_reaches_sandbox(tmp_path: Path) -> None:
    executor = RecordingExecutor()
    tools = _tools(tmp_path, executor)
    arguments = _arguments()
    arguments["approval_token"] = "invalid.invalid"

    response = await tools.verify_vulnerability(**arguments)

    assert response["ok"] is False
    assert response["error"]["code"] == "PERMISSION_DENIED"
    assert executor.calls == 0


@pytest.mark.asyncio
async def test_same_approval_token_cannot_be_replayed(tmp_path: Path) -> None:
    executor = RecordingExecutor()
    tools = _tools(tmp_path, executor)
    arguments = _arguments()

    first = await tools.verify_vulnerability(**arguments)
    second = await tools.verify_vulnerability(**arguments)

    assert first["ok"] is True
    assert second["ok"] is False
    assert second["error"]["code"] == "CONFLICT"
    assert executor.calls == 1


@pytest.mark.asyncio
async def test_sandbox_timeout_becomes_bounded_inconclusive_artifact(tmp_path: Path) -> None:
    executor = RecordingExecutor(timed_out=True)
    tools = _tools(tmp_path, executor)

    response = await tools.verify_vulnerability(**_arguments())

    assert response["ok"] is True
    assert response["data"]["verdict"] == "inconclusive"
    assert response["data"]["execution"]["timed_out"] is True
    assert len(response["artifacts"]) == 1
