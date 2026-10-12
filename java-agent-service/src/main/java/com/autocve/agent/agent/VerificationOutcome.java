package com.autocve.agent.agent;

import com.autocve.agent.agent.model.VerificationPlan;
import com.autocve.agent.mcp.dto.McpArtifactRef;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerificationOutcome(
        @JsonProperty("approval_id") String approvalId,
        @JsonProperty("finding_id") String findingId,
        String verdict,
        List<String> evidence,
        JsonNode execution,
        List<McpArtifactRef> artifacts,
        VerificationPlan plan,
        @JsonProperty("model_runtime") String modelRuntime,
        @JsonProperty("error_code") String errorCode) {

    public VerificationOutcome {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
