package com.autocve.agent.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autocve.agent.AutoCveAgentApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint;
import org.springframework.context.ConfigurableApplicationContext;

class HealthEndpointIntegrationTest {

    @Test
    void actuatorHealthAndMetricsEndpointsExposeRegisteredState() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        AutoCveAgentApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=ERROR")
                .run("--spring.ai.mcp.client.enabled=false")) {
            assertTrue(context.containsBean("flyway"));
            assertEquals(
                    "UP",
                    context.getBean(HealthEndpoint.class).health().getStatus().getCode());
            var metric = context.getBean(MetricsEndpoint.class)
                    .metric("autocve.agent.audits", List.of());
            assertNotNull(metric);
            assertEquals("autocve.agent.audits", metric.getName());
        }
    }
}
