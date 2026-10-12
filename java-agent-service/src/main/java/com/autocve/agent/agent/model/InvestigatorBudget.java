package com.autocve.agent.agent.model;

import java.time.Duration;

public record InvestigatorBudget(
        int maxIterations,
        long maxInputTokens,
        Duration maxDuration) {

    public InvestigatorBudget {
        if (maxIterations < 1 || maxIterations > 50) {
            throw new IllegalArgumentException("maxIterations must be between 1 and 50");
        }
        if (maxInputTokens < 100 || maxInputTokens > 1_000_000) {
            throw new IllegalArgumentException("maxInputTokens must be between 100 and 1000000");
        }
        if (maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
    }
}
