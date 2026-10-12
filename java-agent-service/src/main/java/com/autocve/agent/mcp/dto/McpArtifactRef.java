package com.autocve.agent.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record McpArtifactRef(
        @JsonProperty("artifact_id") String artifactId,
        @JsonProperty("media_type") String mediaType,
        String summary) {
}
