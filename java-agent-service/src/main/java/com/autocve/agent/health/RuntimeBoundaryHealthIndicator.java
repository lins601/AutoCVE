package com.autocve.agent.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("runtimeBoundary")
public class RuntimeBoundaryHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        return Health.up()
                .withDetail("implementationDay", 3)
                .withDetail("contractVersion", "1.0.0")
                .withDetail("mcpTransport", "spring-ai-jdk-streamable-http")
                .build();
    }
}
