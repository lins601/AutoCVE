package com.autocve.agent.mcp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * Internal gateway request. The MCP tool arguments themselves remain flat and
 * are governed by the shared JSON Schema.
 */
public record McpToolRequest(
        @NotNull SecurityToolName tool,
        @Valid @NotNull McpCallContext context,
        @NotNull JsonNode arguments) {
}
