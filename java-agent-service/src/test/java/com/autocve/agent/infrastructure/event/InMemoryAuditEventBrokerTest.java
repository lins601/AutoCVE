package com.autocve.agent.infrastructure.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.autocve.agent.workflow.AgentWorkflowProperties;
import com.autocve.agent.workflow.AuditStage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryAuditEventBrokerTest {

    @Test
    void replaysOnlyEventsAfterTheRequestedSequence() {
        AgentWorkflowProperties properties = new AgentWorkflowProperties();
        InMemoryAuditEventBroker broker = new InMemoryAuditEventBroker(properties);
        var first = broker.publish(
                "audit-1", "audit.created", AuditStage.CREATED, "created", Map.of());
        broker.publish(
                "audit-1", "stage.started", AuditStage.PREPARING, "started", Map.of());

        var replay = broker.events("audit-1", first.sequence());

        assertEquals(1, replay.size());
        assertEquals("stage.started", replay.getFirst().type());
    }
}
