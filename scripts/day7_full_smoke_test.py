#!/usr/bin/env python3
"""Run the Day 7 create -> Agent search -> HITL -> sandbox -> report smoke test."""

from __future__ import annotations

import argparse
import json
import socket
import threading
import time
import urllib.request
import uuid
from typing import Any


def request_json(method: str, url: str, payload: dict[str, Any] | None = None) -> Any:
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if method == "POST" and url.endswith("/agent-audits"):
        request.add_header("Idempotency-Key", f"day7-{uuid.uuid4()}")
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode())


def require_http(url: str, contains: bytes | None = None) -> None:
    with urllib.request.urlopen(url, timeout=10) as response:
        body = response.read()
        if response.status != 200 or (contains is not None and contains not in body):
            raise RuntimeError(f"health check failed: {url} HTTP {response.status}")


def require_socket(host: str, port: int) -> None:
    with socket.create_connection((host, port), timeout=5):
        return


def wait_status(endpoint: str, audit_id: str, expected: set[str], timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        snapshot = request_json("GET", f"{endpoint}/{audit_id}")
        if snapshot["status"] in expected:
            return snapshot
        if snapshot["status"] in {"FAILED", "CANCELED", "COMPLETED"}:
            raise RuntimeError(f"audit stopped early: {snapshot}")
        time.sleep(0.5)
    raise TimeoutError(f"audit {audit_id} did not reach {sorted(expected)}")


def collect_sse(url: str, events: list[dict[str, Any]], stop: threading.Event) -> None:
    request = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            for raw in response:
                if stop.is_set():
                    return
                line = raw.decode(errors="replace").strip()
                if line.startswith("data:"):
                    events.append(json.loads(line[5:].strip()))
    except (OSError, ValueError):
        if not stop.is_set():
            raise


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--frontend-url", default="http://127.0.0.1:13000")
    parser.add_argument("--python-url", default="http://127.0.0.1:18000")
    parser.add_argument("--java-url", default="http://127.0.0.1:18081")
    parser.add_argument("--mcp-port", type=int, default=18082)
    parser.add_argument("--timeout", type=float, default=240)
    args = parser.parse_args()

    require_http(f"{args.python_url}/health")
    require_http(f"{args.java_url}/actuator/health", b'"status":"UP"')
    require_http(f"{args.frontend_url}/audit-tasks", b"<div id=\"root\"></div>")
    require_http(f"{args.frontend_url}/java-mcp-audit", b"<div id=\"root\"></div>")
    require_socket("127.0.0.1", args.mcp_port)
    require_socket("127.0.0.1", 16379)
    require_socket("127.0.0.1", 15432)

    endpoint = f"{args.java_url}/api/v2/agent-audits"
    audit = request_json(
        "POST",
        endpoint,
        {
            "project_id": args.project_id,
            "target_paths": ["."],
            "target_vulnerabilities": ["secret", "injection", "authorization"],
            "verification_level": "sandbox",
            "model_profile": "default",
            "scanners": ["gitleaks"],
            "budgets": {
                "max_iterations": 4,
                "max_input_tokens": 10000,
                "max_duration_seconds": 90,
            },
        },
    )
    audit_id = audit["id"]
    events: list[dict[str, Any]] = []
    stop = threading.Event()
    stream = threading.Thread(
        target=collect_sse,
        args=(f"{endpoint}/{audit_id}/events", events, stop),
        daemon=True,
    )
    stream.start()

    wait_status(endpoint, audit_id, {"WAITING_APPROVAL"}, args.timeout)
    approvals = request_json("GET", f"{endpoint}/{audit_id}/approvals")
    if not approvals:
        raise RuntimeError("workflow did not create a HITL approval")
    for index, approval in enumerate(approvals):
        decision = "approve" if index == 0 else "reject"
        request_json(
            "POST",
            f"{endpoint}/{audit_id}/approvals/{approval['id']}/{decision}",
            {
                "action_digest": approval["action_digest"],
                "decided_by": "day7-smoke-reviewer",
            },
        )

    result = wait_status(endpoint, audit_id, {"COMPLETED"}, args.timeout)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        tools = {event.get("data", {}).get("tool") for event in events}
        if {"search_code", "verify_vulnerability"}.issubset(tools):
            break
        time.sleep(0.1)
    stop.set()
    tools = {event.get("data", {}).get("tool") for event in events}
    if "search_code" not in tools:
        raise RuntimeError(f"Investigator autonomous search was not observed: {sorted(str(x) for x in tools)}")
    verifications = (result.get("result") or {}).get("verifications") or []
    artifacts = [artifact for item in verifications for artifact in (item.get("artifacts") or [])]
    if not artifacts:
        raise RuntimeError("completed report contains no reviewable verification artifact")

    print(json.dumps({
        "audit_id": audit_id,
        "status": result["status"],
        "mcp_tools": sorted(str(tool) for tool in tools if tool),
        "sandbox_verification": True,
        "approval_count": len(approvals),
        "verification_artifacts": len(artifacts),
        "python_entry_healthy": True,
        "java_frontend_entry_healthy": True,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
