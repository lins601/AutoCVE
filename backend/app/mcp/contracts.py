from __future__ import annotations

import json
import os
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Dict


CONTRACT_VERSION = "1.0.0"
MAX_RESPONSE_BYTES = 256 * 1024
_DEFAULT_CONTRACT_PATH = (
    Path(__file__).resolve().parents[3] / "contracts" / "mcp" / "security-tools.schema.json"
)
_CONTRACT_PATH = Path(os.environ.get("AUTOCVE_MCP_CONTRACT_PATH", _DEFAULT_CONTRACT_PATH))


class McpErrorCode(str, Enum):
    INVALID_ARGUMENT = "INVALID_ARGUMENT"
    PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    CONFLICT = "CONFLICT"
    TOOL_TIMEOUT = "TOOL_TIMEOUT"
    SANDBOX_ERROR = "SANDBOX_ERROR"
    DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class SecurityToolName(str, Enum):
    PROFILE_REPOSITORY = "profile_repository"
    SEARCH_CODE = "search_code"
    START_SECURITY_SCAN = "start_security_scan"
    GET_SCAN_JOB = "get_scan_job"
    GET_SCAN_FINDINGS = "get_scan_findings"
    VERIFY_VULNERABILITY = "verify_vulnerability"


@dataclass(frozen=True)
class McpCallContext:
    request_id: str
    trace_id: str
    audit_id: str
    project_id: str


def load_contract_schema() -> Dict[str, Any]:
    """Load the one canonical contract used by both language runtimes."""
    with _CONTRACT_PATH.open(encoding="utf-8") as contract_file:
        return json.load(contract_file)
