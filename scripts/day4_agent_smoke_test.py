#!/usr/bin/env python3
"""Run a Day 4 REST, workflow and SSE smoke test against java-agent-service."""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
import uuid
from typing import Any


def request_json(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
) -> dict[str, Any]:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=body, method=method)
    request.add_header("Accept", "application/json")
    if body is not None:
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} failed: HTTP {error.code} {detail}") from error


def replay_events(url: str, timeout_seconds: float = 5.0) -> list[str]:
    request = urllib.request.Request(url, method="GET")
    request.add_header("Accept", "text/event-stream")
    names: list[str] = []
    deadline = time.monotonic() + timeout_seconds
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
        while time.monotonic() < deadline:
            line = response.readline().decode("utf-8", errors="replace").strip()
            if line.startswith("event:"):
                name = line.partition(":")[2].strip()
                names.append(name)
                if name in {"audit.completed", "audit.failed", "audit.canceled"}:
                    break
    return names


def verify_cancellation(endpoint: str, payload: dict[str, Any]) -> str:
    key = f"day4-cancel-{uuid.uuid4()}"
    created = request_json("POST", endpoint, payload, {"Idempotency-Key": key})
    audit_id = str(created["id"])
    canceled = request_json("POST", f"{endpoint}/{audit_id}/cancel")
    if canceled.get("status") != "CANCELED":
        raise RuntimeError(f"cancellation was not accepted: {canceled}")
    time.sleep(0.5)
    snapshot = request_json("GET", f"{endpoint}/{audit_id}")
    if snapshot.get("status") != "CANCELED":
        raise RuntimeError(f"canceled audit resumed unexpectedly: {snapshot}")
    return audit_id


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--scanner", default="gitleaks")
    parser.add_argument("--timeout", type=float, default=120.0)
    args = parser.parse_args()

    key = f"day4-smoke-{uuid.uuid4()}"
    payload = {
        "project_id": args.project_id,
        "target_paths": ["."],
        "target_vulnerabilities": ["secret"],
        "verification_level": "sandbox",
        "model_profile": "default",
        "scanners": [args.scanner],
        "budgets": {
            "max_iterations": 4,
            "max_input_tokens": 10000,
            "max_duration_seconds": 60,
        },
    }
    endpoint = f"{args.base_url.rstrip('/')}/api/v2/agent-audits"
    created = request_json("POST", endpoint, payload, {"Idempotency-Key": key})
    duplicate = request_json("POST", endpoint, payload, {"Idempotency-Key": key})
    audit_id = str(created["id"])
    if duplicate.get("id") != audit_id:
        raise RuntimeError("Idempotency-Key created two audit runs")

    deadline = time.monotonic() + args.timeout
    snapshot = created
    while snapshot.get("status") in {"CREATED", "RUNNING"}:
        if time.monotonic() >= deadline:
            raise TimeoutError(f"audit {audit_id} did not finish within {args.timeout}s")
        time.sleep(0.5)
        snapshot = request_json("GET", f"{endpoint}/{audit_id}")
    if snapshot.get("status") != "TRIAGED" or snapshot.get("current_stage") != "TRIAGING":
        raise RuntimeError(f"unexpected terminal audit state: {snapshot}")

    event_names = replay_events(f"{endpoint}/{audit_id}/events")
    required_events = {
        "audit.created",
        "stage.started",
        "tool.started",
        "agent.iteration.started",
        "audit.completed",
    }
    missing = sorted(required_events - set(event_names))
    if missing:
        raise RuntimeError(f"SSE replay is missing events: {missing}")

    cancellation_audit_id = verify_cancellation(endpoint, payload)
    result = snapshot.get("result") or {}
    print(json.dumps(
        {
            "audit_id": audit_id,
            "idempotency_verified": True,
            "cancellation_audit_id": cancellation_audit_id,
            "cancellation_verified": True,
            "status": snapshot.get("status"),
            "current_stage": snapshot.get("current_stage"),
            "finding_count": len(result.get("findings") or []),
            "stats": result.get("stats"),
            "sse_event_count": len(event_names),
            "sse_events": event_names,
        },
        ensure_ascii=False,
        indent=2,
    ))


if __name__ == "__main__":
    main()
