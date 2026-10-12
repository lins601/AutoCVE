package com.autocve.agent.workflow;

import com.autocve.agent.infrastructure.event.AuditEventPublisher;
import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditRunRepository;
import com.autocve.agent.infrastructure.persistence.AiAuditStepEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditStepRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AuditStepLifecycle {

    private final AiAuditRunRepository runs;
    private final AiAuditStepRepository steps;
    private final AuditEventPublisher events;

    public AuditStepLifecycle(
            AiAuditRunRepository runs,
            AiAuditStepRepository steps,
            AuditEventPublisher events) {
        this.runs = runs;
        this.steps = steps;
        this.events = events;
    }

    public AiAuditStepEntity start(String auditId, AuditStage stage, String input) {
        AiAuditRunEntity run = activeRun(auditId);
        Instant now = Instant.now();
        run.start(stage, now);
        runs.save(run);
        int attempt = Math.toIntExact(steps.countByAuditIdAndStage(auditId, stage) + 1L);
        AiAuditStepEntity step = steps.save(new AiAuditStepEntity(
                UUID.randomUUID().toString(),
                auditId,
                stage,
                attempt,
                digest(input),
                now));
        events.publish(
                auditId,
                "stage.started",
                stage,
                stage.name() + " started",
                Map.of("step_id", step.getId(), "attempt", attempt));
        return step;
    }

    public void complete(AiAuditStepEntity step, String summary, String checkpointJson) {
        activeRun(step.getAuditId());
        step.complete(summary, checkpointJson, Instant.now());
        steps.save(step);
        events.publish(
                step.getAuditId(),
                "stage.completed",
                step.getStage(),
                summary,
                Map.of("step_id", step.getId(), "attempt", step.getAttempt()));
    }

    public void failRunningStep(String auditId, String summary) {
        steps.findFirstByAuditIdAndStatusOrderByStartedAtDesc(auditId, "RUNNING")
                .ifPresent(step -> {
                    step.fail(summary, Instant.now());
                    steps.save(step);
                });
    }

    public void abandonRunningSteps(String auditId, String summary) {
        Instant now = Instant.now();
        for (AiAuditStepEntity step : steps.findByAuditIdAndStatus(auditId, "RUNNING")) {
            step.interrupt(summary, now);
            steps.save(step);
        }
    }

    public boolean isCanceled(String auditId) {
        return runs.findById(auditId)
                .map(run -> run.isCancelRequested() || run.getCurrentStage() == AuditStage.CANCELED)
                .orElse(true);
    }

    public AiAuditRunEntity activeRun(String auditId) {
        AiAuditRunEntity run = runs.findById(auditId)
                .orElseThrow(() -> new IllegalStateException("Audit disappeared: " + auditId));
        if (run.isCancelRequested() || run.getCurrentStage() == AuditStage.CANCELED) {
            throw new AuditCanceledException("Audit was canceled");
        }
        return run;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        }
        catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
