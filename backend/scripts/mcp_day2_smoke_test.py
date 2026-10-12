"""Smoke-test the Day 2 server through the official Streamable HTTP client."""

from __future__ import annotations

import argparse
import asyncio
import json
import uuid
from typing import Any

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


def _arguments(project_id: str) -> dict[str, str]:
    suffix = uuid.uuid4().hex[:12]
    return {
        "request_id": f"smoke-{suffix}",
        "trace_id": f"trace-{suffix}",
        "audit_id": f"audit-{suffix}",
        "project_id": project_id,
    }


def _structured_result(result: Any) -> dict[str, Any]:
    payload = result.structuredContent
    if not isinstance(payload, dict):
        raise RuntimeError("MCP tool did not return structured content")
    if not payload.get("ok"):
        raise RuntimeError(f"MCP tool failed: {payload.get('error')}")
    return payload


async def run(url: str, project_id: str, query: str) -> dict[str, Any]:
    async with streamable_http_client(url) as (read_stream, write_stream, _):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()
            listed_tools = await session.list_tools()
            tool_names = [tool.name for tool in listed_tools.tools]
            if not {"profile_repository", "search_code"}.issubset(set(tool_names)):
                raise RuntimeError(f"Day 2 tools are missing: {tool_names}")

            profile_result = await session.call_tool(
                "profile_repository",
                arguments={**_arguments(project_id), "target_paths": ["."]},
            )
            profile = _structured_result(profile_result)

            search_result = await session.call_tool(
                "search_code",
                arguments={
                    **_arguments(project_id),
                    "query": query,
                    "top_k": 5,
                },
            )
            search = _structured_result(search_result)

            return {
                "server_url": url,
                "tools": tool_names,
                "profile": {
                    "file_count": profile["data"]["file_count"],
                    "languages": profile["data"]["languages"][:5],
                    "frameworks": profile["data"]["frameworks"],
                },
                "search": {
                    "mode": search["data"]["search_mode"],
                    "result_count": search["data"]["result_count"],
                    "files": [
                        result["file_path"] for result in search["data"]["results"]
                    ],
                },
            }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8001/mcp")
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--query", default="authorization permission admin")
    arguments = parser.parse_args()
    result = asyncio.run(run(arguments.url, arguments.project_id, arguments.query))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
