package com.autocve.agent.application;

import com.autocve.agent.domain.AuditRunStatus;
import com.autocve.agent.infrastructure.event.AuditEventPublisher;
import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditRunRepository;
import com.autocve.agent.observability.AgentMetrics;
import com.autocve.agent.workflow.AuditStage;
import com.autocve.agent.workflow.AuditStepLifecycle;
import com.autocve.agent.workflow.AuditWorkflow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditCommandService {

    private final AiAuditRunRepository runs;
    private final AuditQueryService queries;
    private final AuditWorkflow workflow;
    private final AuditEventPublisher events;
    private final ApprovalService approvals;
    private final AuditStepLifecycle lifecycle;
    private final AgentMetrics metrics;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;

    public AuditCommandService(
            AiAuditRunRepository runs,
            AuditQueryService queries,
            AuditWorkflow workflow,
            AuditEventPublisher events,
            ApprovalService approvals,
            AuditStepLifecycle lifecycle,
            AgentMetrics metrics,
            ExecutorService auditWorkflowExecutor,
            ObjectMapper objectMapper) {
        this.runs = runs;
        this.queries = queries;
        this.workflow = workflow;
        this.events = events;
        this.approvals = approvals;
        this.lifecycle = lifecycle;
        this.metrics = metrics;
        this.executor = auditWorkflowExecutor;
        this.objectMapper = objectMapper;
    }

    public AuditSnapshot create(AuditRequestSpec request, String idempotencyKey) {
        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedKey != null) {
            var existing = runs.findByIdempotencyKey(normalizedKey);
            if (existing.isPresent()) {
                if (!existing.get().getProjectId().equals(request.projectId())) {
                    throw new AuditConflictException(
                            "Idempotency-Key is already bound to another project");
                }
                return queries.get(existing.get().getId());
            }
        }
        String requestJson = write(request);
        String auditId = UUID.randomUUID().toString();
        AiAuditRunEntity run = new AiAuditRunEntity(
                auditId,
                normalizedKey,
                request.projectId(),
                request.modelProfile(),
                requestJson,
                Instant.now());
        try {
            runs.saveAndFlush(run);
        }
        catch (DataIntegrityViolationException conflict) {
            if (normalizedKey != null) {
                return runs.findByIdempotencyKey(normalizedKey)
                        .map(existing -> queries.get(existing.getId()))
                        .orElseThrow(() -> conflict);
            }
            throw conflict;
        }
        events.publish(
                auditId,
                "audit.created",
                AuditStage.CREATED,
                "Agent audit created",
                Map.of("project_id", request.projectId()));
        metrics.auditCreated();
        executor.execute(() -> workflow.run(auditId));
        return queries.get(auditId);
    }

    public AuditSnapshot cancel(String auditId) {
        AiAuditRunEntity run = queries.require(auditId);
        if (run.getStatus().isTerminal()) {
            return queries.get(auditId);
        }
        run.requestCancel(Instant.now());
        runs.saveAndFlush(run);
        events.publish(
                auditId,
                "audit.canceled",
                AuditStage.CANCELED,
                "Agent audit cancellation accepted",
                Map.of());
        metrics.auditCanceled();
        return queries.get(auditId);
    }

    public List<ApprovalSnapshot> listApprovals(String auditId) {
        queries.require(auditId);
        return approvals.list(auditId);
    }

    public ApprovalSnapshot approve(
            String auditId, String approvalId, String actionDigest, String decidedBy) {
        requireWaitingForApproval(auditId);
        ApprovalSnapshot snapshot = approvals.approve(
                auditId, approvalId, actionDigest, decidedBy);
        resumeWhenAllDecisionsExist(auditId);
        return snapshot;
    }

    public ApprovalSnapshot reject(
            String auditId, String approvalId, String actionDigest, String decidedBy) {
        requireWaitingForApproval(auditId);
        ApprovalSnapshot snapshot = approvals.reject(
                auditId, approvalId, actionDigest, decidedBy);
        resumeWhenAllDecisionsExist(auditId);
        return snapshot;
    }

    public AuditSnapshot retry(String auditId) {
        AiAuditRunEntity run = queries.require(auditId);
        if (run.getStatus() != AuditRunStatus.FAILED) {
            throw new AuditConflictException("Only a failed audit can be retried");
        }
        AuditStage resumeStage = run.getFailureStage() == null
                ? AuditStage.PREPARING
                : run.getFailureStage();
        lifecycle.abandonRunningSteps(auditId, "Interrupted attempt replaced by manual retry");
        run.resume(resumeStage);
        runs.saveAndFlush(run);
        events.publish(
                auditId,
                "audit.retry.requested",
                resumeStage,
                "Failed audit scheduled from its latest durable checkpoint",
                Map.of("resume_stage", resumeStage.name()));
        executor.execute(() -> workflow.run(auditId));
        return queries.get(auditId);
    }

    private void requireWaitingForApproval(String auditId) {
        AiAuditRunEntity run = queries.require(auditId);
        if (run.getStatus() != AuditRunStatus.WAITING_APPROVAL) {
            throw new AuditConflictException("Audit is not waiting for an approval decision");
        }
    }

    private void resumeWhenAllDecisionsExist(String auditId) {
        if (!approvals.allDecided(auditId)) {
            return;
        }
        AiAuditRunEntity run = queries.require(auditId);
        if (run.getStatus() != AuditRunStatus.WAITING_APPROVAL) {
            return;
        }
        run.resume(AuditStage.WAITING_APPROVAL);
        runs.saveAndFlush(run);
        events.publish(
                auditId,
                "audit.resumed",
                AuditStage.WAITING_APPROVAL,
                "All verification approval decisions are durable; workflow resumed",
                Map.of());
        executor.execute(() -> workflow.run(auditId));
    }

    private String write(AuditRequestSpec request) {
        try {
            return objectMapper.writeValueAsString(request);
        }
        catch (Exception exception) {
            throw new IllegalArgumentException("Audit request cannot be serialized", exception);
        }
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 128 characters");
        }
        return normalized;
    }
}
