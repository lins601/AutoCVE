"""Redis-backed state and idempotency for MCP scan jobs."""

from __future__ import annotations

import asyncio
import base64
import binascii
import copy
import json
import os
from collections.abc import Sequence
from typing import Any, Protocol

from redis.exceptions import WatchError

from app.core.config import settings

DEFAULT_TTL_SECONDS = 7 * 24 * 60 * 60
KEY_PREFIX = "autocve:mcp:scan"


class McpScanError(Exception):
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


class McpScanJobStore(Protocol):
    async def create_job(self, job: dict[str, Any]) -> tuple[dict[str, Any], bool]: ...

    async def get_job(self, job_id: str) -> dict[str, Any] | None: ...

    async def update_job(self, job_id: str, **changes: Any) -> dict[str, Any]: ...

    async def replace_findings(self, job_id: str, findings: Sequence[dict[str, Any]]) -> None: ...

    async def list_findings(self, job_id: str) -> list[dict[str, Any]]: ...


class RedisMcpScanJobStore:
    def __init__(
        self,
        *,
        redis_client: Any | None = None,
        redis_url: str | None = None,
        ttl_seconds: int | None = None,
    ) -> None:
        self._redis = redis_client
        self._redis_url = redis_url or settings.REDIS_URL
        self._ttl_seconds = int(
            ttl_seconds or os.environ.get("MCP_SCAN_JOB_TTL_SECONDS", DEFAULT_TTL_SECONDS)
        )
        self._owns_client = redis_client is None

    async def _client(self) -> Any:
        if self._redis is None:
            from redis.asyncio import Redis

            self._redis = Redis.from_url(self._redis_url, decode_responses=True)
        return self._redis

    @staticmethod
    def _job_key(job_id: str) -> str:
        return f"{KEY_PREFIX}:job:{job_id}"

    @staticmethod
    def _request_key(project_id: str, request_id: str) -> str:
        return f"{KEY_PREFIX}:request:{project_id}:{request_id}"

    @staticmethod
    def _findings_key(job_id: str) -> str:
        return f"{KEY_PREFIX}:findings:{job_id}"

    async def create_job(self, job: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        client = await self._client()
        request_key = self._request_key(str(job["project_id"]), str(job["request_id"]))
        job_key = self._job_key(str(job["job_id"]))
        payload = json.dumps(job, ensure_ascii=False, separators=(",", ":"), default=str)

        try:
            async with client.pipeline(transaction=True) as pipe:
                while True:
                    try:
                        await pipe.watch(request_key)
                        existing_job_id = await pipe.get(request_key)
                        if existing_job_id:
                            await pipe.unwatch()
                            existing = await self.get_job(str(existing_job_id))
                            if existing is None:
                                raise McpScanError(
                                    "DEPENDENCY_UNAVAILABLE",
                                    "scan idempotency record exists without job state",
                                    retryable=True,
                                )
                            if existing.get("request_fingerprint") != job.get(
                                "request_fingerprint"
                            ):
                                raise McpScanError(
                                    "CONFLICT",
                                    "request_id was already used with different scan arguments",
                                    details={"job_id": existing_job_id},
                                )
                            return existing, False

                        pipe.multi()
                        pipe.set(job_key, payload, ex=self._ttl_seconds)
                        pipe.set(
                            request_key,
                            str(job["job_id"]),
                            ex=self._ttl_seconds,
                        )
                        await pipe.execute()
                        return copy.deepcopy(job), True
                    except WatchError:
                        continue
        except McpScanError:
            raise
        except Exception as exc:
            raise McpScanError(
                "DEPENDENCY_UNAVAILABLE",
                "Redis scan state is unavailable",
                retryable=True,
            ) from exc

    async def get_job(self, job_id: str) -> dict[str, Any] | None:
        try:
            raw = await (await self._client()).get(self._job_key(job_id))
        except Exception as exc:
            raise McpScanError(
                "DEPENDENCY_UNAVAILABLE",
                "Redis scan state is unavailable",
                retryable=True,
            ) from exc
        if not raw:
            return None
        value = json.loads(raw)
        return value if isinstance(value, dict) else None

    async def update_job(self, job_id: str, **changes: Any) -> dict[str, Any]:
        job = await self.get_job(job_id)
        if job is None:
            raise McpScanError(
                "INVALID_ARGUMENT",
                "scan job was not found or has expired",
                details={"job_id": job_id},
            )
        job.update(changes)
        try:
            await (await self._client()).set(
                self._job_key(job_id),
                json.dumps(job, ensure_ascii=False, separators=(",", ":"), default=str),
                ex=self._ttl_seconds,
            )
        except Exception as exc:
            raise McpScanError(
                "DEPENDENCY_UNAVAILABLE",
                "Redis scan state is unavailable",
                retryable=True,
            ) from exc
        return job

    async def replace_findings(
        self,
        job_id: str,
        findings: Sequence[dict[str, Any]],
    ) -> None:
        client = await self._client()
        key = self._findings_key(job_id)
        try:
            async with client.pipeline(transaction=True) as pipe:
                pipe.delete(key)
                if findings:
                    pipe.rpush(
                        key,
                        *[
                            json.dumps(item, ensure_ascii=False, separators=(",", ":"), default=str)
                            for item in findings
                        ],
                    )
                    pipe.expire(key, self._ttl_seconds)
                await pipe.execute()
        except Exception as exc:
            raise McpScanError(
                "DEPENDENCY_UNAVAILABLE",
                "Redis scan findings are unavailable",
                retryable=True,
            ) from exc

    async def list_findings(self, job_id: str) -> list[dict[str, Any]]:
        try:
            rows = await (await self._client()).lrange(self._findings_key(job_id), 0, -1)
        except Exception as exc:
            raise McpScanError(
                "DEPENDENCY_UNAVAILABLE",
                "Redis scan findings are unavailable",
                retryable=True,
            ) from exc
        return [item for row in rows if isinstance((item := json.loads(row)), dict)]

    async def close(self) -> None:
        if not self._owns_client or self._redis is None:
            return
        result = self._redis.aclose()
        if asyncio.iscoroutine(result):
            await result


class InMemoryMcpScanJobStore:
    """Deterministic test/local store with the same idempotency behavior."""

    def __init__(self) -> None:
        self.jobs: dict[str, dict[str, Any]] = {}
        self.requests: dict[tuple[str, str], str] = {}
        self.findings: dict[str, list[dict[str, Any]]] = {}
        self._lock = asyncio.Lock()

    async def create_job(self, job: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        key = (str(job["project_id"]), str(job["request_id"]))
        async with self._lock:
            existing_id = self.requests.get(key)
            if existing_id:
                existing = self.jobs[existing_id]
                if existing.get("request_fingerprint") != job.get("request_fingerprint"):
                    raise McpScanError(
                        "CONFLICT",
                        "request_id was already used with different scan arguments",
                        details={"job_id": existing_id},
                    )
                return copy.deepcopy(existing), False
            self.jobs[str(job["job_id"])] = copy.deepcopy(job)
            self.requests[key] = str(job["job_id"])
            return copy.deepcopy(job), True

    async def get_job(self, job_id: str) -> dict[str, Any] | None:
        job = self.jobs.get(job_id)
        return copy.deepcopy(job) if job else None

    async def update_job(self, job_id: str, **changes: Any) -> dict[str, Any]:
        if job_id not in self.jobs:
            raise McpScanError("INVALID_ARGUMENT", "scan job was not found or has expired")
        self.jobs[job_id].update(copy.deepcopy(changes))
        return copy.deepcopy(self.jobs[job_id])

    async def replace_findings(
        self,
        job_id: str,
        findings: Sequence[dict[str, Any]],
    ) -> None:
        self.findings[job_id] = copy.deepcopy(list(findings))

    async def list_findings(self, job_id: str) -> list[dict[str, Any]]:
        return copy.deepcopy(self.findings.get(job_id, []))


def encode_cursor(job_id: str, offset: int) -> str:
    raw = json.dumps({"job_id": job_id, "offset": offset}, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def decode_cursor(cursor: str | None, job_id: str) -> int:
    if cursor in (None, ""):
        return 0
    try:
        padded = str(cursor) + "=" * (-len(str(cursor)) % 4)
        value = json.loads(base64.urlsafe_b64decode(padded).decode())
        offset = int(value["offset"])
        if value.get("job_id") != job_id or offset < 0:
            raise ValueError
        return offset
    except (
        binascii.Error,
        KeyError,
        TypeError,
        UnicodeDecodeError,
        ValueError,
    ) as exc:
        raise McpScanError(
            "INVALID_ARGUMENT",
            "cursor is invalid for this scan job",
            details={"job_id": job_id},
        ) from exc
