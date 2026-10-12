package com.autocve.agent.workflow;

import com.autocve.agent.infrastructure.persistence.AiAuditStepRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class WorkflowCheckpointService {

    private final AiAuditStepRepository steps;
    private final ObjectMapper objectMapper;

    public WorkflowCheckpointService(
            AiAuditStepRepository steps, ObjectMapper objectMapper) {
        this.steps = steps;
        this.objectMapper = objectMapper;
    }

    public Optional<JsonNode> read(String auditId, AuditStage stage) {
        return steps.findFirstByAuditIdAndStageAndStatusOrderByAttemptDesc(
                        auditId, stage, "COMPLETED")
                .map(step -> parse(step.getCheckpointJson(), stage));
    }

    public boolean completed(String auditId, AuditStage stage) {
        return read(auditId, stage).isPresent();
    }

    private JsonNode parse(String json, AuditStage stage) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(stage + " checkpoint is empty");
        }
        try {
            return objectMapper.readTree(json);
        }
        catch (Exception exception) {
            throw new IllegalStateException(stage + " checkpoint is invalid JSON", exception);
        }
    }
}
