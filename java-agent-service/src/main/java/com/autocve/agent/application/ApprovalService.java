package com.autocve.agent.application;

import com.autocve.agent.domain.ApprovalStatus;
import com.autocve.agent.infrastructure.event.AuditEventPublisher;
import com.autocve.agent.infrastructure.persistence.AiApprovalRequestEntity;
import com.autocve.agent.infrastructure.persistence.AiApprovalRequestRepository;
import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import com.autocve.agent.observability.AgentMetrics;
import com.autocve.agent.workflow.AuditStage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ApprovalService {

    private final AiApprovalRequestRepository approvals;
    private final ActionDigestService digests;
    private final ApprovalProperties properties;
    private final AuditEventPublisher events;
    private final AgentMetrics metrics;
    private final ObjectMapper objectMapper;

    public ApprovalService(
            AiApprovalRequestRepository approvals,
            ActionDigestService digests,
            ApprovalProperties properties,
            AuditEventPublisher events,
            AgentMetrics metrics,
            ObjectMapper objectMapper) {
        this.approvals = approvals;
        this.digests = digests;
        this.properties = properties;
        this.events = events;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<AiApprovalRequestEntity> ensureRequests(
            AiAuditRunEntity run, JsonNode triageResult) {
        List<AiApprovalRequestEntity> result = new ArrayList<>();
        JsonNode findings = triageResult.path("findings");
        if (!findings.isArray()) {
            return List.of();
        }
        Instant now = Instant.now();
        for (JsonNode finding : findings) {
            if (result.size() >= properties.getMaxRequests()
                    || !finding.path("needs_verification").asBoolean(false)) {
                continue;
            }
            String findingId = finding.path("finding_id").asText("");
            if (findingId.isBlank()) {
                throw new IllegalStateException("Triaged finding is missing finding_id");
            }
            String digest = digests.digest(run.getId(), run.getProjectId(), finding);
            AiApprovalRequestEntity entity = approvals
                    .findByAuditIdAndFindingIdAndActionDigest(run.getId(), findingId, digest)
                    .orElseGet(() -> approvals.save(new AiApprovalRequestEntity(
                            UUID.randomUUID().toString(),
                            run.getId(),
                            findingId,
                            digest,
                            write(finding),
                            riskLevel(finding),
                            now,
                            now.plus(properties.getRequestTtl()))));
            result.add(entity);
            if (entity.getCreatedAt().equals(now)) {
                events.publish(
                        run.getId(),
                        "approval.requested",
                        AuditStage.WAITING_APPROVAL,
                        "Verification approval requested",
                        Map.of(
                                "approval_id", entity.getId(),
                                "finding_id", findingId,
                                "action_digest", digest));
            }
        }
        return List.copyOf(result);
    }

    @Transactional
    public ApprovalSnapshot approve(
            String auditId, String approvalId, String actionDigest, String actor) {
        AiApprovalRequestEntity approval = require(auditId, approvalId);
        requireDigest(approval, actionDigest);
        try {
            approval.approve(normalizeActor(actor), Instant.now());
        }
        catch (IllegalStateException exception) {
            throw new AuditConflictException(exception.getMessage());
        }
        approvals.saveAndFlush(approval);
        events.publish(
                auditId,
                "approval.approved",
                AuditStage.WAITING_APPROVAL,
                "Verification approval granted",
                Map.of("approval_id", approvalId, "finding_id", approval.getFindingId()));
        metrics.approvalDecision("approved");
        return snapshot(approval);
    }

    @Transactional
    public ApprovalSnapshot reject(
            String auditId, String approvalId, String actionDigest, String actor) {
        AiApprovalRequestEntity approval = require(auditId, approvalId);
        requireDigest(approval, actionDigest);
        try {
            approval.reject(normalizeActor(actor), Instant.now());
        }
        catch (IllegalStateException exception) {
            throw new AuditConflictException(exception.getMessage());
        }
        approvals.saveAndFlush(approval);
        events.publish(
                auditId,
                "approval.rejected",
                AuditStage.WAITING_APPROVAL,
                "Verification approval rejected",
                Map.of("approval_id", approvalId, "finding_id", approval.getFindingId()));
        metrics.approvalDecision("rejected");
        return snapshot(approval);
    }

    @Transactional
    public AiApprovalRequestEntity claimApproved(String auditId, String approvalId) {
        AiApprovalRequestEntity approval = require(auditId, approvalId);
        try {
            approval.consume(Instant.now());
        }
        catch (IllegalStateException exception) {
            throw new AuditConflictException(exception.getMessage());
        }
        return approvals.saveAndFlush(approval);
    }

    @Transactional
    public void storeResult(String auditId, String approvalId, JsonNode result) {
        AiApprovalRequestEntity approval = require(auditId, approvalId);
        approval.storeResult(write(result));
        approvals.saveAndFlush(approval);
    }

    @Transactional
    public List<ApprovalSnapshot> list(String auditId) {
        expirePending(auditId);
        return approvals.findByAuditIdOrderByCreatedAtAsc(auditId).stream()
                .map(this::snapshot)
                .toList();
    }

    @Transactional
    public List<AiApprovalRequestEntity> entities(String auditId) {
        expirePending(auditId);
        return List.copyOf(approvals.findByAuditIdOrderByCreatedAtAsc(auditId));
    }

    @Transactional
    public boolean allDecided(String auditId) {
        expirePending(auditId);
        return approvals.countByAuditIdAndStatus(auditId, ApprovalStatus.PENDING) == 0;
    }

    private void expirePending(String auditId) {
        Instant now = Instant.now();
        List<AiApprovalRequestEntity> pending =
                approvals.findByAuditIdAndStatusOrderByCreatedAtAsc(auditId, ApprovalStatus.PENDING);
        for (AiApprovalRequestEntity approval : pending) {
            ApprovalStatus before = approval.getStatus();
            approval.expire(now);
            if (before != approval.getStatus()) {
                approvals.save(approval);
                events.publish(
                        auditId,
                        "approval.expired",
                        AuditStage.WAITING_APPROVAL,
                        "Verification approval expired",
                        Map.of("approval_id", approval.getId()));
                metrics.approvalDecision("expired");
            }
        }
    }

    private AiApprovalRequestEntity require(String auditId, String approvalId) {
        AiApprovalRequestEntity approval = approvals.findById(approvalId)
                .orElseThrow(() -> new ApprovalNotFoundException(approvalId));
        if (!approval.getAuditId().equals(auditId)) {
            throw new ApprovalNotFoundException(approvalId);
        }
        return approval;
    }

    private ApprovalSnapshot snapshot(AiApprovalRequestEntity approval) {
        return ApprovalSnapshot.from(
                approval,
                read(approval.getRequestPayload()),
                read(approval.getResultJson()));
    }

    private static void requireDigest(AiApprovalRequestEntity approval, String actionDigest) {
        if (!approval.getActionDigest().equals(actionDigest)) {
            throw new AuditConflictException(
                    "Approval action digest does not match the current finding");
        }
    }

    private static String normalizeActor(String actor) {
        String value = actor == null ? "" : actor.trim();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("decided_by must contain 1 to 128 characters");
        }
        return value;
    }

    private static String riskLevel(JsonNode finding) {
        return switch (finding.path("severity").asText("medium")) {
            case "critical" -> "critical";
            case "high" -> "high";
            default -> "medium";
        };
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Approval payload cannot be serialized", exception);
        }
    }

    private JsonNode read(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Stored approval JSON is invalid", exception);
        }
    }
}
