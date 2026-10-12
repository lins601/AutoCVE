"""ARQ submission boundary for MCP security scan jobs."""

from __future__ import annotations

import inspect
from typing import Any

from app.core.config import settings
from app.services.mcp_scan_runtime.store import McpScanError

MCP_SCAN_JOB_NAME = "execute_mcp_security_scan"


class McpSecurityScanQueue:
    def __init__(
        self,
        *,
        arq_pool: Any | None = None,
        redis_url: str | None = None,
        queue_name: str | None = None,
    ) -> None:
        self.arq_pool = arq_pool
        self.redis_url = redis_url or settings.REDIS_URL
        self.queue_name = queue_name or settings.MCP_SCAN_QUEUE_NAME
        self._owns_pool = arq_pool is None

    async def _pool(self) -> Any:
        if self.arq_pool is None:
            from arq import create_pool
            from arq.connections import RedisSettings

            self.arq_pool = await create_pool(
                RedisSettings.from_dsn(self.redis_url),
                default_queue_name=self.queue_name,
            )
        return self.arq_pool

    async def enqueue(self, job_id: str) -> None:
        try:
            pool = await self._pool()
            queued = await pool.enqueue_job(
                MCP_SCAN_JOB_NAME,
                str(job_id),
                _job_id=f"mcp-scan:{job_id}",
                _queue_name=self.queue_name,
            )
            if queued is None:
                raise RuntimeError("ARQ rejected the scan job")
        except Exception as exc:
            raise McpScanError(
                "DEPENDENCY_UNAVAILABLE",
                "ARQ scan queue is unavailable",
                retryable=True,
            ) from exc

    async def close(self) -> None:
        if not self._owns_pool or self.arq_pool is None:
            return
        close = getattr(self.arq_pool, "close", None)
        if callable(close):
            result = close()
            if inspect.isawaitable(result):
                await result
