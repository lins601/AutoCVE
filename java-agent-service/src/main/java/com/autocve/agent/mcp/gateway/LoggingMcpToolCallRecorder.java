package com.autocve.agent.mcp.gateway;

import com.autocve.agent.infrastructure.persistence.AiAuditRunRepository;
import com.autocve.agent.infrastructure.persistence.AiAuditStepRepository;
import com.autocve.agent.infrastructure.persistence.AiToolCallRecordEntity;
import com.autocve.agent.infrastructure.persistence.AiToolCallRecordRepository;
import com.autocve.agent.observability.AgentMetrics;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingMcpToolCallRecorder implements McpToolCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoggingMcpToolCallRecorder.class);
    private final AiAuditRunRepository runs;
    private final AiAuditStepRepository steps;
    private final AiToolCallRecordRepository records;
    private final AgentMetrics metrics;

    public LoggingMcpToolCallRecorder(
            AiAuditRunRepository runs,
            AiAuditStepRepository steps,
            AiToolCallRecordRepository records,
            AgentMetrics metrics) {
        this.runs = runs;
        this.steps = steps;
        this.records = records;
        this.metrics = metrics;
    }

    @Override
    public void record(McpToolCallRecord record) {
        metrics.toolCall(record);
        log.info(
                "mcp_tool_call tool={} request_id={} trace_id={} attempts={} duration_ms={} outcome={} error_code={} argument_names={}",
                record.toolName(),
                record.requestId(),
                record.traceId(),
                record.attempts(),
                record.durationMs(),
                record.outcome(),
                record.errorCode(),
                record.argumentNames());
        try {
            if (record.auditId() == null || runs.findById(record.auditId()).isEmpty()) {
                return;
            }
            String stepId = steps.findFirstByAuditIdAndStatusOrderByStartedAtDesc(
                            record.auditId(), "RUNNING")
                    .map(entity -> entity.getId())
                    .orElse(null);
            records.save(new AiToolCallRecordEntity(
                    UUID.randomUUID().toString(),
                    record.auditId(),
                    stepId,
                    record.requestId(),
                    record.traceId(),
                    record.toolName(),
                    record.outcome().toUpperCase(),
                    "argument_names=" + record.argumentNames(),
                    "outcome=" + record.outcome(),
                    record.durationMs(),
                    record.errorCode(),
                    record.recordedAt()));
        }
        catch (RuntimeException persistenceFailure) {
            log.warn(
                    "mcp_tool_call_persistence_failed audit_id={} request_id={} reason={}",
                    record.auditId(),
                    record.requestId(),
                    persistenceFailure.getClass().getSimpleName());
        }
    }
}
