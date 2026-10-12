package com.autocve.agent.infrastructure.event;

import com.autocve.agent.workflow.AuditStage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(
        long sequence,
        @JsonProperty("audit_id") String auditId,
        String type,
        AuditStage stage,
        String summary,
        Map<String, Object> data,
        @JsonProperty("created_at") Instant createdAt) {

    public AuditEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
