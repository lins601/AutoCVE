package com.autocve.agent.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record McpCallContext(
        @JsonProperty("request_id") @NotBlank String requestId,
        @JsonProperty("trace_id") @NotBlank String traceId,
        @JsonProperty("audit_id") @NotBlank String auditId,
        @JsonProperty("project_id") @NotBlank String projectId) {
}
