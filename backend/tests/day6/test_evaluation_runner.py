from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts" / "run_day6_evaluation.py"
SPEC = importlib.util.spec_from_file_location("day6_evaluation", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Day6EvaluationTest(unittest.TestCase):
    def test_evaluation_generates_comparative_json_and_markdown(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            report = MODULE.run(ROOT / "evaluation/day6/ground_truth.json", output)

            self.assertEqual("success", report["status"])
            self.assertEqual(10, report["case_count"])
            self.assertGreater(
                report["metrics"]["scanner_agent"]["recall"],
                report["metrics"]["scanner_only"]["recall"],
            )
            self.assertGreater(report["metrics"]["scanner_agent"]["estimated_tokens"], 0)
            self.assertTrue(report["agent_contributions"]["supplemental_findings"])
            self.assertTrue((output / "day6-evaluation.json").is_file())
            self.assertIn(
                "Scanner+Agent",
                (output / "day6-evaluation.md").read_text(encoding="utf-8"),
            )

    def test_failed_evaluation_never_writes_a_success_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            invalid = root / "invalid.json"
            invalid.write_text(
                json.dumps({"version": "bad", "cases": []}),
                encoding="utf-8",
            )

            with self.assertRaises(ValueError):
                MODULE.run(invalid, root / "reports")

            self.assertFalse((root / "reports/day6-evaluation.json").exists())
            failure = json.loads(
                (root / "reports/day6-evaluation.failed.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual("failed", failure["status"])


if __name__ == "__main__":
    unittest.main()
