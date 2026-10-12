package com.autocve.agent.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autocve.agent.application.ApprovalService;
import com.autocve.agent.domain.AuditRunStatus;
import com.autocve.agent.infrastructure.event.AuditEventPublisher;
import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

class AuditRecoveryCoordinatorTest {

    @Test
    void restartSchedulesCreatedRunningAndDecidedAuditsButKeepsPendingApprovalPaused() {
        AiAuditRunRepository runs = mock(AiAuditRunRepository.class);
        AuditStepLifecycle lifecycle = mock(AuditStepLifecycle.class);
        ApprovalService approvals = mock(ApprovalService.class);
        AuditWorkflow workflow = mock(AuditWorkflow.class);
        AuditEventPublisher events = mock(AuditEventPublisher.class);
        ExecutorService executor = mock(ExecutorService.class);

        AiAuditRunEntity created = run("created");
        AiAuditRunEntity running = run("running");
        running.start(AuditStage.SCANNING, Instant.now());
        AiAuditRunEntity pending = run("pending");
        pending.waitForApproval("{}");
        AiAuditRunEntity decided = run("decided");
        decided.waitForApproval("{}");

        when(runs.findByStatus(AuditRunStatus.CREATED)).thenReturn(List.of(created));
        when(runs.findByStatus(AuditRunStatus.RUNNING)).thenReturn(List.of(running));
        when(runs.findByStatus(AuditRunStatus.WAITING_APPROVAL))
                .thenReturn(List.of(pending, decided));
        when(approvals.allDecided("pending")).thenReturn(false);
        when(approvals.allDecided("decided")).thenReturn(true);

        new AuditRecoveryCoordinator(
                        runs,
                        lifecycle,
                        approvals,
                        workflow,
                        events,
                        executor)
                .recoverDurableWorkflows();

        verify(lifecycle).abandonRunningSteps(
                "running", "Application restarted before this attempt completed");
        verify(runs).saveAndFlush(created);
        verify(runs).saveAndFlush(running);
        verify(runs).saveAndFlush(decided);
        verify(runs, never()).saveAndFlush(pending);
        verify(executor, org.mockito.Mockito.times(3)).execute(any(Runnable.class));
        verify(events).publish(
                eq("pending"),
                eq("audit.recovery.waiting"),
                eq(AuditStage.WAITING_APPROVAL),
                any(String.class),
                any());
    }

    private static AiAuditRunEntity run(String id) {
        return new AiAuditRunEntity(
                id,
                null,
                "project-" + id,
                "default",
                "{}",
                Instant.now());
    }
}
