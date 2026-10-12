package com.autocve.agent.workflow;

import com.autocve.agent.application.ApprovalService;
import com.autocve.agent.domain.AuditRunStatus;
import com.autocve.agent.infrastructure.event.AuditEventPublisher;
import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditRunRepository;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AuditRecoveryCoordinator {

    private final AiAuditRunRepository runs;
    private final AuditStepLifecycle lifecycle;
    private final ApprovalService approvals;
    private final AuditWorkflow workflow;
    private final AuditEventPublisher events;
    private final ExecutorService executor;

    public AuditRecoveryCoordinator(
            AiAuditRunRepository runs,
            AuditStepLifecycle lifecycle,
            ApprovalService approvals,
            AuditWorkflow workflow,
            AuditEventPublisher events,
            ExecutorService auditWorkflowExecutor) {
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.approvals = approvals;
        this.workflow = workflow;
        this.events = events;
        this.executor = auditWorkflowExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverDurableWorkflows() {
        for (AiAuditRunEntity run : runs.findByStatus(AuditRunStatus.CREATED)) {
            schedule(run, AuditStage.CREATED, "New audit recovered after application restart");
        }
        for (AiAuditRunEntity run : runs.findByStatus(AuditRunStatus.RUNNING)) {
            AuditStage stage = run.getCurrentStage();
            lifecycle.abandonRunningSteps(
                    run.getId(), "Application restarted before this attempt completed");
            schedule(run, stage, "Running audit recovered from its latest durable checkpoint");
        }
        for (AiAuditRunEntity run : runs.findByStatus(AuditRunStatus.WAITING_APPROVAL)) {
            if (approvals.allDecided(run.getId())) {
                schedule(
                        run,
                        AuditStage.WAITING_APPROVAL,
                        "Decided approval workflow recovered after application restart");
            }
            else {
                events.publish(
                        run.getId(),
                        "audit.recovery.waiting",
                        AuditStage.WAITING_APPROVAL,
                        "Pending approval remains paused after application restart",
                        Map.of());
            }
        }
    }

    private void schedule(AiAuditRunEntity run, AuditStage stage, String message) {
        run.resume(stage);
        runs.saveAndFlush(run);
        events.publish(
                run.getId(),
                "audit.recovered",
                stage,
                message,
                Map.of("resume_stage", stage.name()));
        executor.execute(() -> workflow.run(run.getId()));
    }
}
