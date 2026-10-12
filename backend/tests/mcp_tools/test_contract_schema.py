from __future__ import annotations

import unittest

from app.mcp import (
    CONTRACT_VERSION,
    MAX_RESPONSE_BYTES,
    McpErrorCode,
    SecurityToolName,
    load_contract_schema,
)


class McpContractSchemaTest(unittest.TestCase):
    def test_python_contract_matches_canonical_json_schema(self) -> None:
        schema = load_contract_schema()

        self.assertEqual(CONTRACT_VERSION, schema["x-autocve-contract-version"])
        self.assertEqual(MAX_RESPONSE_BYTES, schema["x-autocve-max-response-bytes"])
        self.assertEqual(
            {tool.value for tool in SecurityToolName},
            set(schema["x-autocve-tool-names"]),
        )
        self.assertEqual(
            {code.value for code in McpErrorCode},
            set(schema["x-autocve-error-codes"]),
        )
        self.assertEqual(
            {"request_id", "trace_id", "audit_id", "project_id"},
            set(schema["$defs"]["commonRequest"]["required"]),
        )

        definitions = schema["$defs"]
        for definition_name in schema["x-autocve-tool-definitions"].values():
            self.assertIn(definition_name, definitions)

        verification = definitions["verifyVulnerabilityRequest"]["allOf"][1]
        self.assertEqual(
            {
                "approval_id",
                "approval_token",
                "action_digest",
                "finding",
                "verification_mode",
            },
            set(verification["required"]),
        )
        self.assertEqual("^[a-f0-9]{64}$", verification["properties"]["action_digest"]["pattern"])
        finding = definitions["findingForVerification"]
        self.assertFalse(finding["additionalProperties"])
        self.assertEqual(
            {"finding_id", "title", "category", "file_path", "line_start", "evidence"},
            set(finding["required"]),
        )


if __name__ == "__main__":
    unittest.main()
