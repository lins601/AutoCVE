package com.autocve.agent.observability;

import com.autocve.agent.agent.InvestigatorOutcome;
import com.autocve.agent.mcp.gateway.McpToolCallRecord;
import com.autocve.agent.workflow.AuditStage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final Counter auditsCreated;
    private final Counter auditsCompleted;
    private final Counter auditsCanceled;
    private final Timer auditDuration;
    private final DistributionSummary investigationTokens;
    private final DistributionSummary investigationIterations;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.auditsCreated = registry.counter("autocve.agent.audits", "outcome", "created");
        this.auditsCompleted = registry.counter("autocve.agent.audits", "outcome", "completed");
        this.auditsCanceled = registry.counter("autocve.agent.audits", "outcome", "canceled");
        this.auditDuration = Timer.builder("autocve.agent.audit.duration")
                .description("End-to-end duration of completed Java Agent audits")
                .register(registry);
        this.investigationTokens = DistributionSummary.builder("autocve.agent.investigation.tokens")
                .description("Estimated input and output tokens used by Investigator runs")
                .baseUnit("tokens")
                .register(registry);
        this.investigationIterations = DistributionSummary.builder("autocve.agent.investigation.iterations")
                .description("Investigator iterations per non-checkpoint execution")
                .register(registry);
    }

    public void auditCreated() {
        auditsCreated.increment();
    }

    public void auditCompleted(Duration duration) {
        auditsCompleted.increment();
        auditDuration.record(duration.isNegative() ? Duration.ZERO : duration);
    }

    public void auditFailed(AuditStage stage) {
        registry.counter(
                        "autocve.agent.audits",
                        "outcome",
                        "failed",
                        "stage",
                        stage == null ? "UNKNOWN" : stage.name())
                .increment();
    }

    public void auditCanceled() {
        auditsCanceled.increment();
    }

    public void investigation(InvestigatorOutcome outcome) {
        investigationTokens.record(Math.max(0L, outcome.estimatedTokens()));
        investigationIterations.record(Math.max(0, outcome.iterations()));
        registry.counter(
                        "autocve.agent.investigations",
                        "termination",
                        boundedTag(outcome.terminationReason()))
                .increment();
    }

    public void approvalDecision(String decision) {
        registry.counter(
                        "autocve.agent.approval.decisions",
                        "decision",
                        boundedTag(decision))
                .increment();
    }

    public void toolCall(McpToolCallRecord record) {
        String tool = boundedTag(record.toolName());
        String outcome = boundedTag(record.outcome());
        registry.counter(
                        "autocve.mcp.tool.calls", "tool", tool, "outcome", outcome)
                .increment();
        Timer.builder("autocve.mcp.tool.duration")
                .description("Java-to-MCP tool latency")
                .tags("tool", tool, "outcome", outcome)
                .register(registry)
                .record(Duration.ofMillis(Math.max(0L, record.durationMs())));
    }

    private static String boundedTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }
}
