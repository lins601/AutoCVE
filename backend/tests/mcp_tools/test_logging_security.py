from __future__ import annotations

import logging
import unittest

from app.mcp.logging_security import log_unexpected_failure


class McpSafeLoggingTest(unittest.TestCase):
    def test_unexpected_failure_log_omits_api_key_and_source(self) -> None:
        secret = "sk-test-secret-value-123456789"
        source = "def vulnerable():\n    return 'complete sensitive source'"
        error = RuntimeError(f"api_key={secret}\n{source}")

        with self.assertLogs("autocve.mcp.test", level=logging.ERROR) as captured:
            log_unexpected_failure(
                logging.getLogger("autocve.mcp.test"),
                "tool_failed",
                error,
            )

        text = "\n".join(captured.output)
        self.assertIn("RuntimeError", text)
        self.assertNotIn(secret, text)
        self.assertNotIn("complete sensitive source", text)


if __name__ == "__main__":
    unittest.main()
