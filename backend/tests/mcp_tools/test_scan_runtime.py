from __future__ import annotations

from pathlib import Path

import pytest

from app.mcp.contracts import McpCallContext
from app.services.mcp_scan_runtime.queue import MCP_SCAN_JOB_NAME, McpSecurityScanQueue
from app.services.mcp_scan_runtime.runner import ExistingSecurityToolExecutor, McpScanJobRunner
from app.services.mcp_scan_runtime.service import McpScanService
from app.services.mcp_scan_runtime.store import InMemoryMcpScanJobStore, McpScanError
from app.services.project_workspace import ProjectWorkspaceError, ResolvedProjectWorkspace


class StaticResolver:
    def __init__(self, root: Path) -> None:
        self.root = root

    async def resolve(self, project_id: str) -> ResolvedProjectWorkspace:
        return ResolvedProjectWorkspace(project_id, "fixture", "local_directory", self.root)


class RecordingQueue:
    def __init__(self) -> None:
        self.job_ids: list[str] = []

    async def enqueue(self, job_id: str) -> None:
        self.job_ids.append(job_id)


class FakeArqPool:
    def __init__(self) -> None:
        self.calls: list[tuple[tuple, dict]] = []

    async def enqueue_job(self, *args, **kwargs):
        self.calls.append((args, kwargs))
        return object()


class FakeExecutor:
    async def execute(self, **kwargs):
        assert kwargs["scanners"] == ["semgrep"]
        return (
            [
                {
                    "finding_id": "finding-1",
                    "scanner": "semgrep",
                    "severity": "high",
                    "title": "SQL injection",
                    "file_path": "src/app.py",
                    "line_start": 8,
                    "evidence": ["unsafe query"],
                }
            ],
            [
                {
                    "scanner": "semgrep",
                    "target_path": ".",
                    "status": "completed",
                    "findings_count": 1,
                }
            ],
        )


@pytest.fixture
def scan_fixture(tmp_path: Path):
    repository = tmp_path / "repository"
    (repository / "src").mkdir(parents=True)
    (repository / "src" / "app.py").write_text("print('ok')\n", encoding="utf-8")
    store = InMemoryMcpScanJobStore()
    queue = RecordingQueue()
    service = McpScanService(
        resolver=StaticResolver(repository),
        store=store,
        queue=queue,
    )
    context = McpCallContext("request-1", "trace-1", "audit-1", "project-1")
    return repository, store, queue, service, context


@pytest.mark.asyncio
async def test_start_scan_is_idempotent_by_request_id(scan_fixture) -> None:
    _, _, queue, service, context = scan_fixture

    first, first_warnings = await service.start_scan(
        context,
        scanners=["semgrep"],
        target_paths=["."],
        timeout_seconds=60,
    )
    second, second_warnings = await service.start_scan(
        context,
        scanners=["semgrep"],
        target_paths=["."],
        timeout_seconds=60,
    )

    assert first["job_id"] == second["job_id"]
    assert first["deduplicated"] is False
    assert second["deduplicated"] is True
    assert first_warnings == []
    assert second_warnings
    assert queue.job_ids == [first["job_id"]]


@pytest.mark.asyncio
async def test_reusing_request_id_with_different_arguments_conflicts(scan_fixture) -> None:
    _, _, _, service, context = scan_fixture
    await service.start_scan(
        context,
        scanners=["semgrep"],
        target_paths=["."],
        timeout_seconds=60,
    )

    with pytest.raises(McpScanError) as error:
        await service.start_scan(
            context,
            scanners=["gitleaks"],
            target_paths=["."],
            timeout_seconds=60,
        )

    assert error.value.code == "CONFLICT"


@pytest.mark.asyncio
async def test_start_scan_rejects_target_path_traversal(scan_fixture) -> None:
    _, _, queue, service, context = scan_fixture

    with pytest.raises(ProjectWorkspaceError) as error:
        await service.start_scan(
            context,
            scanners=["semgrep"],
            target_paths=["../outside"],
            timeout_seconds=60,
        )

    assert error.value.code == "PERMISSION_DENIED"
    assert queue.job_ids == []


@pytest.mark.asyncio
async def test_scan_findings_are_filtered_and_cursor_paginated(scan_fixture) -> None:
    _, store, _, service, context = scan_fixture
    job, _ = await service.start_scan(
        context,
        scanners=["semgrep"],
        target_paths=["."],
        timeout_seconds=60,
    )
    await store.replace_findings(
        job["job_id"],
        [
            {"finding_id": "1", "severity": "high"},
            {"finding_id": "2", "severity": "low"},
            {"finding_id": "3", "severity": "high"},
        ],
    )
    await store.update_job(job["job_id"], status="completed", findings_count=3)

    first, _ = await service.get_findings(
        context,
        job_id=job["job_id"],
        cursor=None,
        limit=1,
        severity_filter=["high"],
    )
    second, _ = await service.get_findings(
        context,
        job_id=job["job_id"],
        cursor=first["next_cursor"],
        limit=1,
        severity_filter=["high"],
    )

    assert [item["finding_id"] for item in first["items"]] == ["1"]
    assert [item["finding_id"] for item in second["items"]] == ["3"]
    assert second["next_cursor"] is None


@pytest.mark.asyncio
async def test_worker_runner_persists_normalized_findings(scan_fixture) -> None:
    repository, store, _, service, context = scan_fixture
    job, _ = await service.start_scan(
        context,
        scanners=["semgrep"],
        target_paths=["."],
        timeout_seconds=60,
    )
    runner = McpScanJobRunner(
        store=store,
        resolver=StaticResolver(repository),
        executor=FakeExecutor(),
    )

    await runner.run(job["job_id"])

    stored_job = await store.get_job(job["job_id"])
    assert stored_job["status"] == "completed"
    assert stored_job["findings_count"] == 1
    assert (await store.list_findings(job["job_id"]))[0]["finding_id"] == "finding-1"


@pytest.mark.asyncio
async def test_arq_queue_uses_stable_job_id() -> None:
    pool = FakeArqPool()
    queue = McpSecurityScanQueue(arq_pool=pool, queue_name="scan:q")

    await queue.enqueue("job-1")

    args, kwargs = pool.calls[0]
    assert args == (MCP_SCAN_JOB_NAME, "job-1")
    assert kwargs == {"_job_id": "mcp-scan:job-1", "_queue_name": "scan:q"}


def test_existing_semgrep_result_is_normalized_without_raw_payload() -> None:
    findings = ExistingSecurityToolExecutor._normalize_result(
        scanner="semgrep",
        target_path=".",
        result={
            "metadata": {
                "findings_count": 1,
                "findings": [
                    {
                        "check_id": "python.sql-injection",
                        "path": "src/app.py",
                        "start": {"line": 8},
                        "extra": {
                            "severity": "ERROR",
                            "message": "Unsafe SQL construction",
                            "lines": "cursor.execute(query + user_input)",
                        },
                    }
                ],
            }
        },
    )

    assert findings[0]["severity"] == "high"
    assert findings[0]["file_path"] == "src/app.py"
    assert findings[0]["line_start"] == 8
    assert "extra" not in findings[0]
