"""End-to-end Day 3 smoke test through the official Python MCP client."""

from __future__ import annotations

import argparse
import asyncio
import json
import uuid
from typing import Any

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

EXPECTED_TOOLS = {
    "profile_repository",
    "search_code",
    "start_security_scan",
    "get_scan_job",
    "get_scan_findings",
}


def _context(project_id: str, request_id: str, audit_id: str) -> dict[str, str]:
    return {
        "request_id": request_id,
        "trace_id": f"trace-{request_id}",
        "audit_id": audit_id,
        "project_id": project_id,
    }


def _structured(result: Any) -> dict[str, Any]:
    payload = result.structuredContent
    if not isinstance(payload, dict):
        raise RuntimeError("MCP tool did not return structured content")
    if not payload.get("ok"):
        raise RuntimeError(f"MCP tool failed: {payload.get('error')}")
    return payload


async def run(
    url: str,
    project_id: str,
    scanner: str,
    poll_timeout: int,
) -> dict[str, Any]:
    suffix = uuid.uuid4().hex[:12]
    audit_id = f"audit-day3-{suffix}"
    start_request_id = f"scan-day3-{suffix}"

    async with streamable_http_client(url) as (read_stream, write_stream, _):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()
            listed = await session.list_tools()
            tool_names = {tool.name for tool in listed.tools}
            if not EXPECTED_TOOLS.issubset(tool_names):
                raise RuntimeError(f"Day 3 tools are missing: {sorted(tool_names)}")

            start_arguments: dict[str, Any] = {
                **_context(project_id, start_request_id, audit_id),
                "scanners": [scanner],
                "target_paths": ["."],
                "timeout_seconds": 120,
            }
            first = _structured(
                await session.call_tool("start_security_scan", arguments=start_arguments)
            )
            duplicate = _structured(
                await session.call_tool("start_security_scan", arguments=start_arguments)
            )
            job_id = str(first["data"]["job_id"])
            if duplicate["data"]["job_id"] != job_id or not duplicate["data"][
                "deduplicated"
            ]:
                raise RuntimeError("request_id idempotency check failed")

            status = str(first["data"]["status"])
            job: dict[str, Any] = first["data"]
            deadline = asyncio.get_running_loop().time() + poll_timeout
            poll_number = 0
            while status in {"queued", "running"}:
                if asyncio.get_running_loop().time() >= deadline:
                    raise TimeoutError(f"scan job {job_id} did not reach a terminal state")
                await asyncio.sleep(0.5)
                poll_number += 1
                polled = _structured(
                    await session.call_tool(
                        "get_scan_job",
                        arguments={
                            **_context(
                                project_id,
                                f"poll-{poll_number}-{suffix}",
                                audit_id,
                            ),
                            "job_id": job_id,
                        },
                    )
                )
                job = polled["data"]
                status = str(job["status"])

            findings = _structured(
                await session.call_tool(
                    "get_scan_findings",
                    arguments={
                        **_context(project_id, f"findings-{suffix}", audit_id),
                        "job_id": job_id,
                        "limit": 10,
                    },
                )
            )
            return {
                "server_url": url,
                "tools": sorted(tool_names),
                "job_id": job_id,
                "idempotency_verified": True,
                "status": status,
                "scanner_runs": job.get("scanner_runs", []),
                "findings_count": job.get("findings_count", 0),
                "returned_findings": len(findings["data"]["items"]),
            }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8001/mcp")
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--scanner", default="gitleaks")
    parser.add_argument("--poll-timeout", type=int, default=90)
    arguments = parser.parse_args()
    result = asyncio.run(
        run(
            arguments.url,
            arguments.project_id,
            arguments.scanner,
            arguments.poll_timeout,
        )
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
