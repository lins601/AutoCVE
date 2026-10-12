package com.autocve.agent.infrastructure.persistence;

import com.autocve.agent.domain.ApprovalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "ai_approval_requests")
public class AiApprovalRequestEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "audit_id", length = 36, nullable = false)
    private String auditId;

    @Column(name = "finding_id", length = 128, nullable = false)
    private String findingId;

    @Column(name = "action_type", length = 64, nullable = false)
    private String actionType;

    @Column(name = "risk_level", length = 32, nullable = false)
    private String riskLevel;

    @Column(name = "action_digest", length = 64, nullable = false)
    private String actionDigest;

    @Column(name = "request_payload", nullable = false, columnDefinition = "TEXT")
    private String requestPayload;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private ApprovalStatus status;

    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiApprovalRequestEntity() {}

    public AiApprovalRequestEntity(
            String id,
            String auditId,
            String findingId,
            String actionDigest,
            String requestPayload,
            String riskLevel,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.auditId = auditId;
        this.findingId = findingId;
        this.actionType = "verify_vulnerability";
        this.riskLevel = riskLevel;
        this.actionDigest = actionDigest;
        this.requestPayload = requestPayload;
        this.status = ApprovalStatus.PENDING;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getAuditId() { return auditId; }
    public String getFindingId() { return findingId; }
    public String getActionType() { return actionType; }
    public String getRiskLevel() { return riskLevel; }
    public String getActionDigest() { return actionDigest; }
    public String getRequestPayload() { return requestPayload; }
    public ApprovalStatus getStatus() { return status; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public String getResultJson() { return resultJson; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }

    public void approve(String actor, Instant now) {
        requirePending(now);
        status = ApprovalStatus.APPROVED;
        decidedBy = actor;
        decidedAt = now;
    }

    public void reject(String actor, Instant now) {
        requirePending(now);
        status = ApprovalStatus.REJECTED;
        decidedBy = actor;
        decidedAt = now;
    }

    public void expire(Instant now) {
        if (status == ApprovalStatus.PENDING && !now.isBefore(expiresAt)) {
            status = ApprovalStatus.EXPIRED;
            decidedBy = "system-expiry";
            decidedAt = now;
        }
    }

    public void consume(Instant now) {
        if (status != ApprovalStatus.APPROVED) {
            throw new IllegalStateException("Only an approved action can be consumed");
        }
        status = ApprovalStatus.CONSUMED;
        consumedAt = now;
    }

    public void storeResult(String resultJson) {
        if (status != ApprovalStatus.CONSUMED) {
            throw new IllegalStateException("Verification result requires a consumed approval");
        }
        this.resultJson = resultJson;
    }

    private void requirePending(Instant now) {
        expire(now);
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("Approval has already been decided or expired");
        }
    }
}
