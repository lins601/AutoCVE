package com.autocve.agent.application;

import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditRunRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditQueryService {

    private final AiAuditRunRepository runs;
    private final ObjectMapper objectMapper;

    public AuditQueryService(AiAuditRunRepository runs, ObjectMapper objectMapper) {
        this.runs = runs;
        this.objectMapper = objectMapper;
    }

    public AuditSnapshot get(String auditId) {
        AiAuditRunEntity run = require(auditId);
        return AuditSnapshot.from(run, readResult(run.getResultJson()));
    }

    public AiAuditRunEntity require(String auditId) {
        return runs.findById(auditId).orElseThrow(() -> new AuditNotFoundException(auditId));
    }

    private JsonNode readResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Stored audit result is invalid JSON", exception);
        }
    }
}
