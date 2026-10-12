package com.autocve.agent.infrastructure.persistence;

import com.autocve.agent.domain.AuditRunStatus;
import com.autocve.agent.workflow.AuditStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "ai_audit_runs")
public class AiAuditRunEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "idempotency_key", length = 128, unique = true)
    private String idempotencyKey;

    @Column(name = "project_id", length = 128, nullable = false)
    private String projectId;

    @Column(name = "python_task_id", length = 128)
    private String pythonTaskId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private AuditRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", length = 32, nullable = false)
    private AuditStage currentStage;

    @Column(name = "model_profile", length = 128, nullable = false)
    private String modelProfile;

    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
    private String requestJson;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 32)
    private AuditStage failureStage;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AiAuditRunEntity() {}

    public AiAuditRunEntity(
            String id,
            String idempotencyKey,
            String projectId,
            String modelProfile,
            String requestJson,
            Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.projectId = projectId;
        this.modelProfile = modelProfile;
        this.requestJson = requestJson;
        this.createdAt = createdAt;
        this.status = AuditRunStatus.CREATED;
        this.currentStage = AuditStage.CREATED;
    }

    public String getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getProjectId() { return projectId; }
    public String getPythonTaskId() { return pythonTaskId; }
    public AuditRunStatus getStatus() { return status; }
    public AuditStage getCurrentStage() { return currentStage; }
    public String getModelProfile() { return modelProfile; }
    public String getRequestJson() { return requestJson; }
    public String getResultJson() { return resultJson; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public AuditStage getFailureStage() { return failureStage; }
    public boolean isCancelRequested() { return cancelRequested; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void start(AuditStage stage, Instant now) {
        status = AuditRunStatus.RUNNING;
        currentStage = stage;
        if (startedAt == null) {
            startedAt = now;
        }
    }

    public void moveTo(AuditStage stage) {
        currentStage = stage;
    }

    public void waitForApproval(String resultJson) {
        this.resultJson = resultJson;
        this.status = AuditRunStatus.WAITING_APPROVAL;
        this.currentStage = AuditStage.WAITING_APPROVAL;
        this.completedAt = null;
    }

    public void resume(AuditStage stage) {
        this.status = AuditRunStatus.RUNNING;
        this.currentStage = stage;
        this.errorCode = null;
        this.errorMessage = null;
        this.failureStage = null;
        this.completedAt = null;
    }

    public void saveIntermediateResult(String resultJson) {
        this.resultJson = resultJson;
    }

    public void complete(String resultJson, Instant now) {
        this.resultJson = resultJson;
        this.status = AuditRunStatus.COMPLETED;
        this.currentStage = AuditStage.COMPLETED;
        this.errorCode = null;
        this.errorMessage = null;
        this.failureStage = null;
        this.completedAt = now;
    }

    public void completeTriage(String resultJson, Instant now) {
        this.resultJson = resultJson;
        this.status = AuditRunStatus.TRIAGED;
        this.currentStage = AuditStage.TRIAGING;
        this.completedAt = now;
    }

    public void requestCancel(Instant now) {
        cancelRequested = true;
        status = AuditRunStatus.CANCELED;
        currentStage = AuditStage.CANCELED;
        completedAt = now;
    }

    public void fail(String code, String message, Instant now) {
        failureStage = currentStage;
        status = AuditRunStatus.FAILED;
        currentStage = AuditStage.FAILED;
        errorCode = code;
        errorMessage = message == null ? null : message.substring(0, Math.min(2000, message.length()));
        completedAt = now;
    }
}
