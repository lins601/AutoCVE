package com.autocve.agent.application;

import com.autocve.agent.domain.ApprovalStatus;
import com.autocve.agent.infrastructure.persistence.AiApprovalRequestEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalSnapshot(
        String id,
        @JsonProperty("audit_id") String auditId,
        @JsonProperty("finding_id") String findingId,
        @JsonProperty("action_type") String actionType,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("action_digest") String actionDigest,
        ApprovalStatus status,
        JsonNode finding,
        @JsonProperty("decided_by") String decidedBy,
        @JsonProperty("decided_at") Instant decidedAt,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("consumed_at") Instant consumedAt,
        JsonNode result,
        long version) {

    public static ApprovalSnapshot from(
            AiApprovalRequestEntity entity, JsonNode finding, JsonNode result) {
        return new ApprovalSnapshot(
                entity.getId(),
                entity.getAuditId(),
                entity.getFindingId(),
                entity.getActionType(),
                entity.getRiskLevel(),
                entity.getActionDigest(),
                entity.getStatus(),
                finding,
                entity.getDecidedBy(),
                entity.getDecidedAt(),
                entity.getExpiresAt(),
                entity.getConsumedAt(),
                result,
                entity.getVersion());
    }
}
