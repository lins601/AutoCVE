"""Dedicated ARQ worker for Day 3 MCP security scans."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from arq.connections import RedisSettings
from arq.worker import func

from app.core.config import settings
from app.services.mcp_scan_runtime.queue import MCP_SCAN_JOB_NAME
from app.services.mcp_scan_runtime.runner import McpScanJobRunner

logger = logging.getLogger(__name__)


async def execute_mcp_security_scan_job(ctx: dict[str, Any], job_id: str) -> None:
    del ctx
    logger.info("MCP scan worker picked job %s", job_id)
    await McpScanJobRunner().run(str(job_id))


class WorkerSettings:
    functions = [func(execute_mcp_security_scan_job, name=MCP_SCAN_JOB_NAME)]
    redis_settings = RedisSettings.from_dsn(settings.REDIS_URL)
    queue_name = settings.MCP_SCAN_QUEUE_NAME
    max_jobs = settings.MCP_SCAN_WORKER_CONCURRENCY
    job_timeout = settings.MCP_SCAN_WORKER_JOB_TIMEOUT_SECONDS
    max_tries = settings.MCP_SCAN_WORKER_MAX_TRIES
    retry_jobs = True


async def run_worker() -> None:
    from arq.worker import Worker

    logging.basicConfig(level=logging.INFO)
    worker = Worker(
        WorkerSettings.functions,
        queue_name=WorkerSettings.queue_name,
        redis_settings=WorkerSettings.redis_settings,
        max_jobs=WorkerSettings.max_jobs,
        job_timeout=WorkerSettings.job_timeout,
        max_tries=WorkerSettings.max_tries,
        retry_jobs=WorkerSettings.retry_jobs,
    )
    await worker.async_run()


def main() -> None:
    asyncio.run(run_worker())


if __name__ == "__main__":
    main()
