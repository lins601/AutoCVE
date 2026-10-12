package com.autocve.agent.infrastructure.persistence;

import com.autocve.agent.workflow.AuditStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_audit_steps")
public class AiAuditStepEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "audit_id", length = 36, nullable = false)
    private String auditId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private AuditStage stage;

    @Column(nullable = false)
    private int attempt;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(name = "input_digest", length = 64, nullable = false)
    private String inputDigest;

    @Column(name = "output_summary", length = 2000)
    private String outputSummary;

    @Column(name = "checkpoint_json", columnDefinition = "TEXT")
    private String checkpointJson;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AiAuditStepEntity() {}

    public AiAuditStepEntity(
            String id,
            String auditId,
            AuditStage stage,
            int attempt,
            String inputDigest,
            Instant startedAt) {
        this.id = id;
        this.auditId = auditId;
        this.stage = stage;
        this.attempt = attempt;
        this.inputDigest = inputDigest;
        this.startedAt = startedAt;
        this.status = "RUNNING";
    }

    public String getId() { return id; }
    public String getAuditId() { return auditId; }
    public AuditStage getStage() { return stage; }
    public int getAttempt() { return attempt; }
    public String getStatus() { return status; }
    public String getInputDigest() { return inputDigest; }
    public String getOutputSummary() { return outputSummary; }
    public String getCheckpointJson() { return checkpointJson; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void complete(String summary, String checkpointJson, Instant now) {
        this.status = "COMPLETED";
        this.outputSummary = truncate(summary);
        this.checkpointJson = checkpointJson;
        this.completedAt = now;
    }

    public void fail(String summary, Instant now) {
        this.status = "FAILED";
        this.outputSummary = truncate(summary);
        this.completedAt = now;
    }

    public void interrupt(String summary, Instant now) {
        if ("RUNNING".equals(status)) {
            this.status = "FAILED";
            this.outputSummary = truncate(summary);
            this.completedAt = now;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.substring(0, Math.min(2000, value.length()));
    }
}
