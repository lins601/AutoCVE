#!/usr/bin/env python3
"""Run deterministic Scanner-only versus Scanner+Agent evaluation."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import tempfile
import time
from typing import Any


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _finding(value: Any, source: str) -> dict[str, Any]:
    _require(isinstance(value, dict), "finding must be an object")
    result = dict(value)
    for field in ("category", "file_path", "title"):
        _require(
            isinstance(result.get(field), str) and result[field].strip(),
            f"finding.{field} is required",
        )
    _require(
        isinstance(result.get("line_start"), int) and result["line_start"] > 0,
        "finding.line_start must be positive",
    )
    evidence = result.get("evidence", [])
    _require(
        isinstance(evidence, list)
        and all(isinstance(item, str) for item in evidence),
        "finding.evidence must be strings",
    )
    result["source"] = str(result.get("source") or source)
    return result


def _load_dataset(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    _require(isinstance(payload, dict), "dataset must be an object")
    cases = payload.get("cases")
    _require(
        isinstance(cases, list) and 8 <= len(cases) <= 12,
        "dataset must contain 8 to 12 cases",
    )
    ids: set[str] = set()
    for case in cases:
        _require(isinstance(case, dict), "case must be an object")
        case_id = case.get("id")
        _require(
            isinstance(case_id, str) and case_id and case_id not in ids,
            "case id must be unique",
        )
        ids.add(case_id)
        _require(
            isinstance(case.get("ground_truth"), list),
            f"{case_id}.ground_truth must be a list",
        )
        for truth in case["ground_truth"]:
            _finding(truth, "ground_truth")
        for mode in ("scanner", "agent"):
            run = case.get(mode)
            _require(isinstance(run, dict), f"{case_id}.{mode} must be an object")
            _require(
                isinstance(run.get("duration_ms"), int)
                and run["duration_ms"] >= 0,
                f"{case_id}.{mode}.duration_ms is invalid",
            )
            calls = run.get("tool_calls")
            _require(isinstance(calls, dict), f"{case_id}.{mode}.tool_calls must be an object")
            success, total = calls.get("success"), calls.get("total")
            _require(
                isinstance(success, int)
                and isinstance(total, int)
                and 0 <= success <= total,
                f"{case_id}.{mode}.tool_calls is invalid",
            )
            _require(
                isinstance(run.get("findings"), list),
                f"{case_id}.{mode}.findings must be a list",
            )
            for finding in run["findings"]:
                _finding(finding, mode)
        _require(
            isinstance(case["agent"].get("estimated_tokens"), int)
            and case["agent"]["estimated_tokens"] >= 0,
            f"{case_id}.agent.estimated_tokens is invalid",
        )
    return payload


def _same_location(left: dict[str, Any], right: dict[str, Any], tolerance: int) -> bool:
    return (
        left["category"].casefold() == right["category"].casefold()
        and left["file_path"] == right["file_path"]
        and abs(int(left["line_start"]) - int(right["line_start"])) <= tolerance
    )


def _deduplicate(findings: list[dict[str, Any]], tolerance: int) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    for finding in findings:
        existing = next((item for item in merged if _same_location(item, finding, tolerance)), None)
        if existing is None:
            merged.append(dict(finding))
            continue
        existing_evidence = list(existing.get("evidence", []))
        for evidence in finding.get("evidence", []):
            if evidence not in existing_evidence:
                existing_evidence.append(evidence)
        existing["evidence"] = existing_evidence
        sources = set(existing["source"].split("+")) | {finding["source"]}
        existing["source"] = "+".join(sorted(sources))
    return merged


def _score(
    truth: list[dict[str, Any]],
    predictions: list[dict[str, Any]],
    tolerance: int,
) -> tuple[int, int, int]:
    matched_truth: set[int] = set()
    true_positive = 0
    for prediction in predictions:
        match = next(
            (
                index
                for index, item in enumerate(truth)
                if index not in matched_truth
                and _same_location(item, prediction, tolerance)
            ),
            None,
        )
        if match is not None:
            matched_truth.add(match)
            true_positive += 1
    return true_positive, len(predictions) - true_positive, len(truth) - true_positive


def _ratio(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 4) if denominator else 1.0


def evaluate(dataset: dict[str, Any]) -> dict[str, Any]:
    tolerance = int(dataset.get("line_tolerance", 3))
    totals = {
        "scanner_only": {
            "tp": 0,
            "fp": 0,
            "fn": 0,
            "duration_ms": 0,
            "estimated_tokens": 0,
            "tool_success": 0,
            "tool_total": 0,
        },
        "scanner_agent": {
            "tp": 0,
            "fp": 0,
            "fn": 0,
            "duration_ms": 0,
            "estimated_tokens": 0,
            "tool_success": 0,
            "tool_total": 0,
        },
    }
    case_results: list[dict[str, Any]] = []
    supplemental: list[dict[str, str]] = []
    evidence_additions: list[dict[str, str]] = []

    for case in dataset["cases"]:
        truth = [_finding(item, "ground_truth") for item in case["ground_truth"]]
        scanner = [_finding(item, "scanner") for item in case["scanner"]["findings"]]
        agent = [_finding(item, "agent") for item in case["agent"]["findings"]]
        combined = _deduplicate([*scanner, *agent], tolerance)
        scanner_score = _score(truth, scanner, tolerance)
        combined_score = _score(truth, combined, tolerance)

        for name, score in (("scanner_only", scanner_score), ("scanner_agent", combined_score)):
            totals[name]["tp"] += score[0]
            totals[name]["fp"] += score[1]
            totals[name]["fn"] += score[2]
        totals["scanner_only"]["duration_ms"] += case["scanner"]["duration_ms"]
        totals["scanner_only"]["tool_success"] += case["scanner"]["tool_calls"]["success"]
        totals["scanner_only"]["tool_total"] += case["scanner"]["tool_calls"]["total"]
        totals["scanner_agent"]["duration_ms"] += (
            case["scanner"]["duration_ms"] + case["agent"]["duration_ms"]
        )
        totals["scanner_agent"]["estimated_tokens"] += case["agent"]["estimated_tokens"]
        totals["scanner_agent"]["tool_success"] += (
            case["scanner"]["tool_calls"]["success"]
            + case["agent"]["tool_calls"]["success"]
        )
        totals["scanner_agent"]["tool_total"] += (
            case["scanner"]["tool_calls"]["total"]
            + case["agent"]["tool_calls"]["total"]
        )

        for finding in agent:
            matches_truth = any(_same_location(finding, item, tolerance) for item in truth)
            matches_scanner = any(_same_location(finding, item, tolerance) for item in scanner)
            if matches_truth and not matches_scanner:
                supplemental.append({"case_id": case["id"], "title": finding["title"]})
            elif matches_truth and matches_scanner and finding.get("evidence"):
                evidence_additions.append({"case_id": case["id"], "title": finding["title"]})

        case_results.append(
            {
                "case_id": case["id"],
                "language": case["language"],
                "ground_truth_count": len(truth),
                "scanner_only": {
                    "tp": scanner_score[0],
                    "fp": scanner_score[1],
                    "fn": scanner_score[2],
                },
                "scanner_agent": {
                    "tp": combined_score[0],
                    "fp": combined_score[1],
                    "fn": combined_score[2],
                },
            }
        )

    metrics: dict[str, Any] = {}
    for name, values in totals.items():
        metrics[name] = {
            **values,
            "precision": _ratio(values["tp"], values["tp"] + values["fp"]),
            "recall": _ratio(values["tp"], values["tp"] + values["fn"]),
            "false_positive_rate": _ratio(values["fp"], values["tp"] + values["fp"]),
            "tool_success_rate": _ratio(values["tool_success"], values["tool_total"]),
        }
    _require(
        supplemental or evidence_additions,
        "evaluation must demonstrate an Agent contribution",
    )
    return {
        "status": "success",
        "dataset_version": dataset["version"],
        "case_count": len(dataset["cases"]),
        "line_tolerance": tolerance,
        "metrics": metrics,
        "agent_contributions": {
            "supplemental_findings": supplemental,
            "additional_evidence": evidence_additions,
        },
        "cases": case_results,
    }


def _markdown(report: dict[str, Any]) -> str:
    scanner = report["metrics"]["scanner_only"]
    agent = report["metrics"]["scanner_agent"]
    lines = [
        "# AutoCVE Day 6 Evaluation Report",
        "",
        f"- Status: `{report['status']}`",
        f"- Dataset: `{report['dataset_version']}` ({report['case_count']} cases)",
        "",
        "| Mode | Precision | Recall | False-positive rate | Tokens | "
        "Duration (ms) | Tool success |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
        f"| Scanner-only | {scanner['precision']:.4f} | "
        f"{scanner['recall']:.4f} | {scanner['false_positive_rate']:.4f} | "
        f"{scanner['estimated_tokens']} | {scanner['duration_ms']} | "
        f"{scanner['tool_success_rate']:.4f} |",
        f"| Scanner+Agent | {agent['precision']:.4f} | "
        f"{agent['recall']:.4f} | {agent['false_positive_rate']:.4f} | "
        f"{agent['estimated_tokens']} | {agent['duration_ms']} | "
        f"{agent['tool_success_rate']:.4f} |",
        "",
        "## Agent contributions",
        "",
    ]
    for item in report["agent_contributions"]["supplemental_findings"]:
        lines.append(f"- Supplemental finding: `{item['case_id']}` — {item['title']}")
    for item in report["agent_contributions"]["additional_evidence"]:
        lines.append(f"- Additional evidence: `{item['case_id']}` — {item['title']}")
    lines.extend(
        [
            "",
            "## Per-case results",
            "",
            "| Case | Scanner TP/FP/FN | Scanner+Agent TP/FP/FN |",
            "| --- | --- | --- |",
        ]
    )
    for case in report["cases"]:
        scanner_case, agent_case = case["scanner_only"], case["scanner_agent"]
        lines.append(
            f"| {case['case_id']} | "
            f"{scanner_case['tp']}/{scanner_case['fp']}/{scanner_case['fn']} | "
            f"{agent_case['tp']}/{agent_case['fp']}/{agent_case['fn']} |"
        )
    return "\n".join(lines) + "\n"


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def run(dataset_path: Path, output_dir: Path) -> dict[str, Any]:
    try:
        report = evaluate(_load_dataset(dataset_path))
        report["generated_at_epoch_ms"] = int(time.time() * 1000)
        _atomic_write(
            output_dir / "day6-evaluation.json",
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        )
        _atomic_write(output_dir / "day6-evaluation.md", _markdown(report))
        failed = output_dir / "day6-evaluation.failed.json"
        if failed.exists():
            failed.unlink()
        return report
    except Exception as exc:
        failure = {"status": "failed", "error_type": type(exc).__name__, "message": str(exc)[:500]}
        _atomic_write(
            output_dir / "day6-evaluation.failed.json",
            json.dumps(failure, ensure_ascii=False, indent=2) + "\n",
        )
        raise


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=Path("evaluation/day6/ground_truth.json"))
    parser.add_argument("--output-dir", type=Path, default=Path("evaluation/day6/reports"))
    args = parser.parse_args()
    report = run(args.dataset, args.output_dir)
    print(
        json.dumps(
            {
                "status": report["status"],
                "case_count": report["case_count"],
                "reports": [
                    str(args.output_dir / "day6-evaluation.json"),
                    str(args.output_dir / "day6-evaluation.md"),
                ],
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
