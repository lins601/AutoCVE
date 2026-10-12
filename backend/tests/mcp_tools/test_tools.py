from __future__ import annotations

from pathlib import Path

import pytest

from app.mcp.responses import McpResponseLimiter
from app.mcp.tools import McpSecurityTools
from app.services.project_workspace import (
    ProjectWorkspaceError,
    ResolvedProjectWorkspace,
)


class StaticResolver:
    def __init__(self, root: Path) -> None:
        self._root = root

    async def resolve(self, project_id: str) -> ResolvedProjectWorkspace:
        if project_id == "missing-project":
            raise ProjectWorkspaceError("PROJECT_NOT_FOUND", "project was not found")
        return ResolvedProjectWorkspace(project_id, "fixture", "local_directory", self._root)


@pytest.fixture
def sample_tools(tmp_path: Path) -> McpSecurityTools:
    repository = tmp_path / "repository"
    repository.mkdir()
    (repository / "app.py").write_text(
        "def load_user(user_id):\n    return db.execute('select * from user where id=' + user_id)\n",
        encoding="utf-8",
    )
    return McpSecurityTools(
        resolver=StaticResolver(repository),
        response_limiter=McpResponseLimiter(tmp_path / "artifacts"),
    )


@pytest.mark.asyncio
async def test_tools_return_unified_success_structure(sample_tools: McpSecurityTools) -> None:
    response = await sample_tools.search_code(
        request_id="req-1",
        trace_id="trace-1",
        audit_id="audit-1",
        project_id="project-1",
        query="execute user input",
        top_k=3,
    )

    assert response["ok"] is True
    assert response["request_id"] == "req-1"
    assert response["trace_id"] == "trace-1"
    assert response["error"] is None
    assert response["data"]["result_count"] == 1
    assert response["artifacts"] == []


@pytest.mark.asyncio
async def test_project_failure_uses_canonical_error_structure(
    sample_tools: McpSecurityTools,
) -> None:
    response = await sample_tools.profile_repository(
        request_id="req-2",
        trace_id="trace-2",
        audit_id="audit-2",
        project_id="missing-project",
    )

    assert response["ok"] is False
    assert response["error"] == {
        "code": "PROJECT_NOT_FOUND",
        "message": "project was not found",
        "retryable": False,
        "details": None,
    }


def test_oversized_output_moves_to_artifact(tmp_path: Path) -> None:
    from app.mcp.contracts import McpCallContext

    limiter = McpResponseLimiter(tmp_path / "artifacts", max_response_bytes=500)
    context = McpCallContext("req-large", "trace-large", "audit-large", "project-large")

    response = limiter.success(context, {"content": "x" * 5000})

    assert response["ok"] is True
    assert response["data"]["truncated"] is True
    assert len(response["artifacts"]) == 1
    stored_files = list((tmp_path / "artifacts" / "audit-large").glob("*.json"))
    assert len(stored_files) == 1
    assert "x" * 100 in stored_files[0].read_text(encoding="utf-8")
