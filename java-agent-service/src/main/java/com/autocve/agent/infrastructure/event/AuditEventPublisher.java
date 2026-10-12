package com.autocve.agent.infrastructure.event;

import com.autocve.agent.workflow.AuditStage;
import java.util.Map;

public interface AuditEventPublisher {
    AuditEvent publish(
            String auditId,
            String type,
            AuditStage stage,
            String summary,
            Map<String, Object> data);
}
