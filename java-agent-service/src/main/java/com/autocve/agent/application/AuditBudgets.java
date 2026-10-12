package com.autocve.agent.application;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuditBudgets(
        @JsonProperty("max_iterations") int maxIterations,
        @JsonProperty("max_input_tokens") long maxInputTokens,
        @JsonProperty("max_duration_seconds") long maxDurationSeconds) {

    public static AuditBudgets defaults() {
        return new AuditBudgets(8, 60_000, 300);
    }
}
