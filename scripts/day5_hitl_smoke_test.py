#!/usr/bin/env python3
"""Exercise Day 5 HITL, approval binding, rejection and verification artifacts."""

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
    status, body = request_http(method, url, payload, headers)
    if status >= 400:
        raise RuntimeError(f"{method} {url} failed: HTTP {status} {body}")
    return body


def request_http(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
) -> tuple[int, dict[str, Any]]:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            body = json.loads(response.read().decode("utf-8"))
            return response.status, body
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {"raw": raw}
        return error.code, body


def create_audit(endpoint: str, project_id: str, scanner: str, suffix: str) -> str:
    payload = {
        "project_id": project_id,
        "target_paths": ["."],
        "target_vulnerabilities": ["secret", "injection", "authorization"],
        "verification_level": "sandbox",
        "model_profile": "default",
        "scanners": [scanner],
        "budgets": {
            "max_iterations": 4,
            "max_input_tokens": 10000,
            "max_duration_seconds": 60,
        },
    }
    key = f"day5-{suffix}-{uuid.uuid4()}"
    created = request_json("POST", endpoint, payload, {"Idempotency-Key": key})
    return str(created["id"])


def wait_for_status(
    endpoint: str,
    audit_id: str,
    expected: set[str],
    timeout_seconds: float,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while True:
        snapshot = request_json("GET", f"{endpoint}/{audit_id}")
        if snapshot.get("status") in expected:
            return snapshot
        if snapshot.get("status") in {"COMPLETED", "TRIAGED", "FAILED", "CANCELED"}:
            raise RuntimeError(f"audit stopped before {sorted(expected)}: {snapshot}")
        if time.monotonic() >= deadline:
            raise TimeoutError(f"audit {audit_id} did not reach {sorted(expected)}")
        time.sleep(0.5)


def decide(
    endpoint: str,
    audit_id: str,
    approval: dict[str, Any],
    decision: str,
) -> dict[str, Any]:
    return request_json(
        "POST",
        f"{endpoint}/{audit_id}/approvals/{approval['id']}/{decision}",
        {
            "action_digest": approval["action_digest"],
            "decided_by": "day5-smoke-reviewer",
        },
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--scanner", default="gitleaks")
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument(
        "--leave-pending",
        action="store_true",
        help="Create one extra pending audit for an external container-restart check.",
    )
    args = parser.parse_args()
    endpoint = f"{args.base_url.rstrip('/')}/api/v2/agent-audits"

    approved_id = create_audit(endpoint, args.project_id, args.scanner, "approved")
    wait_for_status(endpoint, approved_id, {"WAITING_APPROVAL"}, args.timeout)
    approvals = request_json("GET", f"{endpoint}/{approved_id}/approvals")
    if not approvals:
        raise RuntimeError("triage did not create a verification approval")

    selected = approvals[0]
    wrong_status, wrong_body = request_http(
        "POST",
        f"{endpoint}/{approved_id}/approvals/{selected['id']}/approve",
        {"action_digest": "0" * 64, "decided_by": "day5-smoke-reviewer"},
    )
    if wrong_status != 409:
        raise RuntimeError(f"wrong action digest was not rejected: {wrong_status} {wrong_body}")

    decide(endpoint, approved_id, selected, "approve")
    for approval in approvals[1:]:
        decide(endpoint, approved_id, approval, "reject")
    approved_snapshot = wait_for_status(
        endpoint, approved_id, {"COMPLETED"}, args.timeout
    )
    approved_states = request_json("GET", f"{endpoint}/{approved_id}/approvals")
    selected_state = next(value for value in approved_states if value["id"] == selected["id"])
    if selected_state.get("status") != "CONSUMED":
        raise RuntimeError(f"approved action was not consumed: {selected_state}")
    verification = selected_state.get("result") or {}
    if not verification.get("artifacts"):
        raise RuntimeError(f"verification did not return a reviewable artifact: {verification}")

    replay_status, _ = request_http(
        "POST",
        f"{endpoint}/{approved_id}/approvals/{selected['id']}/approve",
        {
            "action_digest": selected["action_digest"],
            "decided_by": "day5-smoke-reviewer",
        },
    )
    if replay_status != 409:
        raise RuntimeError(f"consumed approval was reusable: HTTP {replay_status}")

    rejected_id = create_audit(endpoint, args.project_id, args.scanner, "rejected")
    wait_for_status(endpoint, rejected_id, {"WAITING_APPROVAL"}, args.timeout)
    rejected_approvals = request_json("GET", f"{endpoint}/{rejected_id}/approvals")
    for approval in rejected_approvals:
        decide(endpoint, rejected_id, approval, "reject")
    rejected_snapshot = wait_for_status(
        endpoint, rejected_id, {"COMPLETED"}, args.timeout
    )
    verdicts = [
        item.get("verdict")
        for item in (rejected_snapshot.get("result") or {}).get("verifications", [])
    ]
    if not verdicts or set(verdicts) != {"unverified"}:
        raise RuntimeError(f"rejected audit did not produce an unverified report: {verdicts}")

    pending_id = None
    if args.leave_pending:
        pending_id = create_audit(endpoint, args.project_id, args.scanner, "restart")
        wait_for_status(endpoint, pending_id, {"WAITING_APPROVAL"}, args.timeout)

    print(
        json.dumps(
            {
                "approved_audit_id": approved_id,
                "approved_status": approved_snapshot.get("status"),
                "approved_finding_id": selected.get("finding_id"),
                "verification_verdict": verification.get("verdict"),
                "verification_artifacts": verification.get("artifacts"),
                "wrong_digest_blocked": True,
                "single_use_verified": True,
                "rejected_audit_id": rejected_id,
                "rejected_status": rejected_snapshot.get("status"),
                "rejected_verdicts": verdicts,
                "pending_restart_audit_id": pending_id,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
