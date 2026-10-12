"""Unified MCP response construction and output-size enforcement."""

from __future__ import annotations

import json
import os
import uuid
from pathlib import Path
from typing import Any

from app.mcp.contracts import MAX_RESPONSE_BYTES, McpCallContext


class McpResponseLimiter:
    def __init__(
        self,
        artifact_root: str | Path | None = None,
        max_response_bytes: int = MAX_RESPONSE_BYTES,
    ) -> None:
        configured_root = artifact_root or os.environ.get(
            "AUTOCVE_MCP_ARTIFACT_ROOT", "/tmp/autocve-mcp-artifacts"
        )
        self._artifact_root = Path(configured_root)
        self._max_response_bytes = max_response_bytes

    @staticmethod
    def _serialized_size(value: Any) -> int:
        return len(
            json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str).encode(
                "utf-8"
            )
        )

    def success(
        self,
        context: McpCallContext,
        data: Any,
        *,
        warnings: list[str] | None = None,
        artifacts: list[dict[str, str]] | None = None,
    ) -> dict[str, Any]:
        response: dict[str, Any] = {
            "ok": True,
            "trace_id": context.trace_id,
            "request_id": context.request_id,
            "data": data,
            "warnings": list(warnings or []),
            "artifacts": list(artifacts or []),
            "error": None,
        }
        if self._serialized_size(response) <= self._max_response_bytes:
            return response

        artifact = self._store_artifact(context, data)
        original_size = self._serialized_size(data)
        response["data"] = {
            "truncated": True,
            "original_size_bytes": original_size,
            "summary": "Tool output exceeded the inline MCP response limit and was stored as an artifact.",
        }
        response["warnings"].append(
            f"inline output exceeded {self._max_response_bytes} bytes; full data moved to artifact"
        )
        response["artifacts"] = [artifact]
        return response

    @staticmethod
    def failure(
        context: McpCallContext,
        *,
        code: str,
        message: str,
        retryable: bool = False,
        details: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return {
            "ok": False,
            "trace_id": context.trace_id,
            "request_id": context.request_id,
            "data": None,
            "warnings": [],
            "artifacts": [],
            "error": {
                "code": code,
                "message": message[:2000],
                "retryable": retryable,
                "details": details,
            },
        }

    def _store_artifact(self, context: McpCallContext, data: Any) -> dict[str, str]:
        artifact_id = f"mcp-{uuid.uuid4()}"
        audit_directory = self._artifact_root / context.audit_id
        audit_directory.mkdir(parents=True, exist_ok=True)
        artifact_path = audit_directory / f"{artifact_id}.json"
        temporary_path = audit_directory / f".{artifact_id}.tmp"
        temporary_path.write_text(
            json.dumps(data, ensure_ascii=False, indent=2, default=str), encoding="utf-8"
        )
        temporary_path.replace(artifact_path)
        return {
            "artifact_id": artifact_id,
            "media_type": "application/json",
            "summary": "Complete MCP tool output stored outside the read-only project workspace.",
        }
