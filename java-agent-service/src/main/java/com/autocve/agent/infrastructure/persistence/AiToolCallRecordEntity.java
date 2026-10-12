package com.autocve.agent.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_tool_call_records")
public class AiToolCallRecordEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "audit_id", length = 36, nullable = false)
    private String auditId;

    @Column(name = "step_id", length = 36)
    private String stepId;

    @Column(name = "request_id", length = 128, nullable = false)
    private String requestId;

    @Column(name = "trace_id", length = 128, nullable = false)
    private String traceId;

    @Column(name = "tool_name", length = 128, nullable = false)
    private String toolName;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(name = "input_summary", length = 2000)
    private String inputSummary;

    @Column(name = "output_summary", length = 2000)
    private String outputSummary;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiToolCallRecordEntity() {}

    public AiToolCallRecordEntity(
            String id,
            String auditId,
            String stepId,
            String requestId,
            String traceId,
            String toolName,
            String status,
            String inputSummary,
            String outputSummary,
            long durationMs,
            String errorCode,
            Instant createdAt) {
        this.id = id;
        this.auditId = auditId;
        this.stepId = stepId;
        this.requestId = requestId;
        this.traceId = traceId;
        this.toolName = toolName;
        this.status = status;
        this.inputSummary = inputSummary;
        this.outputSummary = outputSummary;
        this.durationMs = durationMs;
        this.errorCode = errorCode;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getAuditId() { return auditId; }
    public String getStepId() { return stepId; }
    public String getRequestId() { return requestId; }
    public String getTraceId() { return traceId; }
    public String getToolName() { return toolName; }
    public String getStatus() { return status; }
    public String getInputSummary() { return inputSummary; }
    public String getOutputSummary() { return outputSummary; }
    public long getDurationMs() { return durationMs; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
}
