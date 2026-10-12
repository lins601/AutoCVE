package com.autocve.agent.mcp.dto;

import tools.jackson.databind.JsonNode;

public record McpToolError(
        McpErrorCode code,
        String message,
        boolean retryable,
        JsonNode details) {
}
