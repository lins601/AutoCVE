package com.autocve.agent.agent;

import com.autocve.agent.agent.model.InvestigationResult;
import com.fasterxml.jackson.annotation.JsonProperty;

public record InvestigatorOutcome(
        InvestigationResult result,
        int iterations,
        @JsonProperty("estimated_tokens") long estimatedTokens,
        @JsonProperty("termination_reason") String terminationReason,
        @JsonProperty("model_runtime") String modelRuntime) {
}
