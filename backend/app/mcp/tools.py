"""Application layer for read-only and asynchronous scan MCP tools."""

from __future__ import annotations

import asyncio
import logging
import re
from collections.abc import Awaitable, Callable
from typing import Any, Protocol

from app.mcp.contracts import McpCallContext
from app.mcp.logging_security import log_unexpected_failure
from app.mcp.responses import McpResponseLimiter
from app.services.mcp_scan_runtime.service import McpScanService
from app.services.mcp_scan_runtime.store import McpScanError
from app.services.mcp_verification import McpVerificationError, McpVerificationService
from app.services.project_workspace import (
    DatabaseProjectWorkspaceResolver,
    ProjectWorkspaceError,
    ResolvedProjectWorkspace,
    validate_identifier,
)
from app.services.repository_read_service import RepositoryReadService

logger = logging.getLogger(__name__)
_SAFE_RESPONSE_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class ProjectWorkspaceResolver(Protocol):
    async def resolve(self, project_id: str) -> ResolvedProjectWorkspace: ...


class McpSecurityTools:
    def __init__(
        self,
        *,
        resolver: ProjectWorkspaceResolver | None = None,
        repository_service: RepositoryReadService | None = None,
        response_limiter: McpResponseLimiter | None = None,
        scan_service: McpScanService | None = None,
        verification_service: McpVerificationService | None = None,
    ) -> None:
        self._resolver = resolver or DatabaseProjectWorkspaceResolver()
        self._repository_service = repository_service or RepositoryReadService()
        self._responses = response_limiter or McpResponseLimiter()
        self._scan_service = scan_service or McpScanService(resolver=self._resolver)
        self._verification_service = verification_service or McpVerificationService(
            resolver=self._resolver
        )

    @staticmethod
    def _context(
        request_id: str,
        trace_id: str,
        audit_id: str,
        project_id: str,
    ) -> McpCallContext:
        return McpCallContext(
            request_id=validate_identifier(request_id, "request_id"),
            trace_id=validate_identifier(trace_id, "trace_id"),
            audit_id=validate_identifier(audit_id, "audit_id"),
            project_id=validate_identifier(project_id, "project_id"),
        )

    async def profile_repository(
        self,
        *,
        request_id: str,
        trace_id: str,
        audit_id: str,
        project_id: str,
        target_paths: list[str] | None = None,
        exclude_patterns: list[str] | None = None,
    ) -> dict[str, Any]:
        try:
            context = self._context(request_id, trace_id, audit_id, project_id)
        except ProjectWorkspaceError as exc:
            return self._invalid_context_response(request_id, trace_id, exc)

        async def operation() -> tuple[dict[str, Any], list[str]]:
            workspace = await self._resolver.resolve(context.project_id)
            return await asyncio.to_thread(
                self._repository_service.profile_repository,
                workspace.root,
                target_paths=target_paths,
                exclude_patterns=exclude_patterns,
            )

        return await self._execute(context, operation, timeout_seconds=30)

    async def search_code(
        self,
        *,
        request_id: str,
        trace_id: str,
        audit_id: str,
        project_id: str,
        query: str,
        top_k: int = 10,
        path_filter: list[str] | None = None,
        language_filter: list[str] | None = None,
    ) -> dict[str, Any]:
        try:
            context = self._context(request_id, trace_id, audit_id, project_id)
        except ProjectWorkspaceError as exc:
            return self._invalid_context_response(request_id, trace_id, exc)

        async def operation() -> tuple[dict[str, Any], list[str]]:
            workspace = await self._resolver.resolve(context.project_id)
            return await asyncio.to_thread(
                self._repository_service.search_code,
                workspace.root,
                query=query,
                top_k=top_k,
                path_filter=path_filter,
                language_filter=language_filter,
            )

        return await self._execute(context, operation, timeout_seconds=20)

    async def start_security_scan(
        self,
        *,
        request_id: str,
        trace_id: str,
        audit_id: str,
        project_id: str,
        scanners: list[str],
        target_paths: list[str] | None = None,
        timeout_seconds: int = 600,
    ) -> dict[str, Any]:
        try:
            context = self._context(request_id, trace_id, audit_id, project_id)
        except ProjectWorkspaceError as exc:
            return self._invalid_context_response(request_id, trace_id, exc)

        async def operation() -> tuple[dict[str, Any], list[str]]:
            return await self._scan_service.start_scan(
                context,
                scanners=scanners,
                target_paths=target_paths,
                timeout_seconds=timeout_seconds,
            )

        return await self._execute(context, operation, timeout_seconds=15)

    async def get_scan_job(
        self,
        *,
        request_id: str,
        trace_id: str,
        audit_id: str,
        project_id: str,
        job_id: str,
    ) -> dict[str, Any]:
        try:
            context = self._context(request_id, trace_id, audit_id, project_id)
        except ProjectWorkspaceError as exc:
            return self._invalid_context_response(request_id, trace_id, exc)

        async def operation() -> tuple[dict[str, Any], list[str]]:
            return await self._scan_service.get_job(context, job_id=job_id)

        return await self._execute(context, operation, timeout_seconds=5)

    async def get_scan_findings(
        self,
        *,
        request_id: str,
        trace_id: str,
        audit_id: str,
        project_id: str,
        job_id: str,
        cursor: str | None = None,
        limit: int = 50,
        severity_filter: list[str] | None = None,
    ) -> dict[str, Any]:
        try:
            context = self._context(request_id, trace_id, audit_id, project_id)
        except ProjectWorkspaceError as exc:
            return self._invalid_context_response(request_id, trace_id, exc)

        async def operation() -> tuple[dict[str, Any], list[str]]:
            return await self._scan_service.get_findings(
                context,
                job_id=job_id,
                cursor=cursor,
                limit=limit,
                severity_filter=severity_filter,
            )

        return await self._execute(context, operation, timeout_seconds=5)

    async def verify_vulnerability(
        self,
        *,
        request_id: str,
        trace_id: str,
        audit_id: str,
        project_id: str,
        approval_id: str,
        approval_token: str,
        action_digest: str,
        finding: dict[str, Any],
        verification_mode: str,
        timeout_seconds: int = 120,
    ) -> dict[str, Any]:
        try:
            context = self._context(request_id, trace_id, audit_id, project_id)
        except ProjectWorkspaceError as exc:
            return self._invalid_context_response(request_id, trace_id, exc)
        try:
            async with asyncio.timeout(timeout_seconds + 10):
                data, warnings, artifacts = await self._verification_service.verify(
                    context,
                    approval_id=approval_id,
                    approval_token=approval_token,
                    action_digest=action_digest,
                    finding=finding,
                    verification_mode=verification_mode,
                    timeout_seconds=timeout_seconds,
                )
            return self._responses.success(
                context,
                data,
                warnings=warnings,
                artifacts=artifacts,
            )
        except TimeoutError:
            return self._responses.failure(
                context,
                code="TOOL_TIMEOUT",
                message="verification exceeded its bounded timeout",
                retryable=False,
            )
        except (McpVerificationError, McpScanError) as exc:
            return self._responses.failure(
                context,
                code=exc.code,
                message=str(exc),
                retryable=exc.retryable,
                details=exc.details,
            )
        except ProjectWorkspaceError as exc:
            return self._responses.failure(
                context,
                code=exc.code,
                message=str(exc),
                retryable=exc.retryable,
                details=exc.details,
            )
        except Exception as exc:
            log_unexpected_failure(
                logger,
                "unexpected_verification_failure",
                exc,
            )
            return self._responses.failure(
                context,
                code="INTERNAL_ERROR",
                message="unexpected verification failure",
                retryable=False,
            )

    async def _execute(
        self,
        context: McpCallContext,
        operation: Callable[[], Awaitable[tuple[dict[str, Any], list[str]]]],
        *,
        timeout_seconds: int,
    ) -> dict[str, Any]:
        try:
            async with asyncio.timeout(timeout_seconds):
                data, warnings = await operation()
            return self._responses.success(context, data, warnings=warnings)
        except TimeoutError:
            return self._responses.failure(
                context,
                code="TOOL_TIMEOUT",
                message=f"tool exceeded its {timeout_seconds}-second timeout",
                retryable=True,
            )
        except ProjectWorkspaceError as exc:
            return self._responses.failure(
                context,
                code=exc.code,
                message=str(exc),
                retryable=exc.retryable,
                details=exc.details,
            )
        except McpScanError as exc:
            return self._responses.failure(
                context,
                code=exc.code,
                message=str(exc),
                retryable=exc.retryable,
                details=exc.details,
            )
        except Exception as exc:
            log_unexpected_failure(
                logger,
                "unexpected_mcp_tool_failure",
                exc,
            )
            return self._responses.failure(
                context,
                code="INTERNAL_ERROR",
                message="unexpected MCP tool failure",
                retryable=False,
            )

    @staticmethod
    def _invalid_context_response(
        request_id: str,
        trace_id: str,
        error: ProjectWorkspaceError,
    ) -> dict[str, Any]:
        fallback_context = McpCallContext(
            request_id=(
                request_id
                if _SAFE_RESPONSE_IDENTIFIER.fullmatch(str(request_id or ""))
                else "invalid-request"
            ),
            trace_id=(
                trace_id
                if _SAFE_RESPONSE_IDENTIFIER.fullmatch(str(trace_id or ""))
                else "invalid-trace"
            ),
            audit_id="invalid-audit",
            project_id="invalid-project",
        )
        return McpResponseLimiter.failure(
            fallback_context,
            code=error.code,
            message=str(error),
            retryable=error.retryable,
            details=error.details,
        )
