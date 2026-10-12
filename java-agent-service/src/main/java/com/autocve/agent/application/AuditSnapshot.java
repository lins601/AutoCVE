package com.autocve.agent.application;

import com.autocve.agent.domain.AuditRunStatus;
import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import com.autocve.agent.workflow.AuditStage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditSnapshot(
        String id,
        @JsonProperty("project_id") String projectId,
        AuditRunStatus status,
        @JsonProperty("current_stage") AuditStage currentStage,
        @JsonProperty("model_profile") String modelProfile,
        @JsonProperty("cancel_requested") boolean cancelRequested,
        JsonNode result,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("failure_stage") AuditStage failureStage,
        long version,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt) {

    public static AuditSnapshot from(AiAuditRunEntity entity, JsonNode result) {
        return new AuditSnapshot(
                entity.getId(),
                entity.getProjectId(),
                entity.getStatus(),
                entity.getCurrentStage(),
                entity.getModelProfile(),
                entity.isCancelRequested(),
                result,
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getFailureStage(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt());
    }
}
