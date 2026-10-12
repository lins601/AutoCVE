package com.autocve.agent.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.autocve.agent.agent.InvestigatorOutcome;
import com.autocve.agent.agent.model.InvestigationResult;
import com.autocve.agent.mcp.gateway.McpToolCallRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentMetricsTest {

    @Test
    void recordsAuditAgentApprovalAndToolMetricsWithBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.auditCreated();
        metrics.auditCompleted(Duration.ofMillis(25));
        metrics.investigation(new InvestigatorOutcome(
                new InvestigationResult("covered", List.of(), List.of()),
                2,
                512,
                "finalize",
                "test"));
        metrics.approvalDecision("approved");
        metrics.toolCall(new McpToolCallRecord(
                "audit-1",
                "search_code",
                "request-1",
                "trace-1",
                Set.of("query"),
                1,
                12,
                "success",
                null,
                Instant.now()));

        assertEquals(1.0, registry.get("autocve.agent.audits")
                .tag("outcome", "created").counter().count());
        assertEquals(512.0, registry.get("autocve.agent.investigation.tokens").summary().totalAmount());
        assertEquals(1.0, registry.get("autocve.mcp.tool.calls")
                .tags("tool", "search_code", "outcome", "success").counter().count());
        assertNotNull(registry.find("autocve.agent.audit.duration").timer());
    }
}
