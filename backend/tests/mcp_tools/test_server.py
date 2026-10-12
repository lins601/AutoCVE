from __future__ import annotations

import pytest

from app.mcp.server import mcp


@pytest.mark.asyncio
async def test_fastmcp_registers_day2_through_day5_tools() -> None:
    tools = await mcp.list_tools()
    tools_by_name = {tool.name: tool for tool in tools}

    assert set(tools_by_name) == {
        "profile_repository",
        "search_code",
        "start_security_scan",
        "get_scan_job",
        "get_scan_findings",
        "verify_vulnerability",
    }
    for tool in tools_by_name.values():
        assert {
            "request_id",
            "trace_id",
            "audit_id",
            "project_id",
        }.issubset(set(tool.inputSchema.get("required", [])))
    assert "query" in tools_by_name["search_code"].inputSchema["required"]
    assert "scanners" in tools_by_name["start_security_scan"].inputSchema["required"]
    assert "job_id" in tools_by_name["get_scan_job"].inputSchema["required"]
    assert "job_id" in tools_by_name["get_scan_findings"].inputSchema["required"]
    assert {
        "approval_id",
        "approval_token",
        "action_digest",
        "finding",
        "verification_mode",
    }.issubset(set(tools_by_name["verify_vulnerability"].inputSchema["required"]))
