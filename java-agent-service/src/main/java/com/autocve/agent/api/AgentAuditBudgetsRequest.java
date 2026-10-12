package com.autocve.agent.api;

import com.autocve.agent.application.AuditBudgets;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AgentAuditBudgetsRequest(
        @Min(1) @Max(50) @JsonProperty("max_iterations") Integer maxIterations,
        @Min(100) @Max(1_000_000) @JsonProperty("max_input_tokens") Long maxInputTokens,
        @Min(1) @Max(3600) @JsonProperty("max_duration_seconds") Long maxDurationSeconds) {

    AuditBudgets toBudgets() {
        AuditBudgets defaults = AuditBudgets.defaults();
        return new AuditBudgets(
                maxIterations == null ? defaults.maxIterations() : maxIterations,
                maxInputTokens == null ? defaults.maxInputTokens() : maxInputTokens,
                maxDurationSeconds == null
                        ? defaults.maxDurationSeconds()
                        : maxDurationSeconds);
    }
}
