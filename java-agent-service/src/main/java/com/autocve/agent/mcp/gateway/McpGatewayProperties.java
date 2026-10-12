package com.autocve.agent.mcp.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("autocve.mcp.gateway")
public record McpGatewayProperties(int maxAttempts, Duration retryWait) {

    public McpGatewayProperties {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
        }
        if (retryWait == null || retryWait.isNegative()) {
            throw new IllegalArgumentException("retryWait must not be negative");
        }
    }
}
