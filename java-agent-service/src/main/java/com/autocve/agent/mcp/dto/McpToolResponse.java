package com.autocve.agent.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpToolResponse<T>(
        boolean ok,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("request_id") String requestId,
        T data,
        List<String> warnings,
        List<McpArtifactRef> artifacts,
        McpToolError error) {

    public McpToolResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        if (ok && error != null) {
            throw new IllegalArgumentException("A successful MCP response cannot contain an error");
        }
        if (!ok && error == null) {
            throw new IllegalArgumentException("A failed MCP response must contain an error");
        }
    }

    public static <T> McpToolResponse<T> success(
            String traceId,
            String requestId,
            T data,
            List<String> warnings,
            List<McpArtifactRef> artifacts) {
        return new McpToolResponse<>(true, traceId, requestId, data, warnings, artifacts, null);
    }

    public static <T> McpToolResponse<T> failure(
            String traceId,
            String requestId,
            McpToolError error) {
        return new McpToolResponse<>(false, traceId, requestId, null, List.of(), List.of(), error);
    }
}
