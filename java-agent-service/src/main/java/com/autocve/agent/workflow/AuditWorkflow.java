package com.autocve.agent.workflow;

import com.autocve.agent.agent.InvestigatorAgent;
import com.autocve.agent.agent.InvestigatorContext;
import com.autocve.agent.agent.InvestigatorOutcome;
import com.autocve.agent.agent.VerificationAgent;
import com.autocve.agent.agent.VerificationOutcome;
import com.autocve.agent.agent.model.InvestigatorBudget;
import com.autocve.agent.application.ApprovalService;
import com.autocve.agent.application.AuditRequestSpec;
import com.autocve.agent.domain.ApprovalStatus;
import com.autocve.agent.infrastructure.event.AuditEventPublisher;
import com.autocve.agent.infrastructure.persistence.AiApprovalRequestEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditRunRepository;
import com.autocve.agent.infrastructure.persistence.AiAuditStepEntity;
import com.autocve.agent.mcp.dto.McpCallContext;
import com.autocve.agent.mcp.dto.McpToolRequest;
import com.autocve.agent.mcp.dto.McpToolResponse;
import com.autocve.agent.mcp.dto.SecurityToolName;
import com.autocve.agent.mcp.gateway.AgentRole;
import com.autocve.agent.mcp.gateway.SecurityToolGateway;
import com.autocve.agent.mcp.gateway.ToolPermissionPolicy;
import com.autocve.agent.observability.AgentMetrics;
import com.autocve.agent.observability.SensitiveDataRedactor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AuditWorkflow {

    private static final Set<String> TERMINAL_SCAN_STATES =
            Set.of("completed", "failed", "canceled");
    private static final Set<String> ALLOWED_SCANNERS =
            Set.of("semgrep", "gitleaks", "trufflehog", "osv");

    private final AiAuditRunRepository runs;
    private final AuditStepLifecycle lifecycle;
    private final WorkflowCheckpointService checkpoints;
    private final SecurityToolGateway gateway;
    private final ToolPermissionPolicy permissionPolicy;
    private final InvestigatorAgent investigator;
    private final VerificationAgent verificationAgent;
    private final ApprovalService approvals;
    private final AuditEventPublisher events;
    private final AgentWorkflowProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentMetrics metrics;
    private final SensitiveDataRedactor redactor;

    public AuditWorkflow(
            AiAuditRunRepository runs,
            AuditStepLifecycle lifecycle,
            WorkflowCheckpointService checkpoints,
            SecurityToolGateway gateway,
            ToolPermissionPolicy permissionPolicy,
            InvestigatorAgent investigator,
            VerificationAgent verificationAgent,
            ApprovalService approvals,
            AuditEventPublisher events,
            AgentWorkflowProperties properties,
            ObjectMapper objectMapper,
            AgentMetrics metrics,
            SensitiveDataRedactor redactor) {
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.checkpoints = checkpoints;
        this.gateway = gateway;
        this.permissionPolicy = permissionPolicy;
        this.investigator = investigator;
        this.verificationAgent = verificationAgent;
        this.approvals = approvals;
        this.events = events;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.redactor = redactor;
    }

    public void run(String auditId) {
        String traceId = "trace:" + auditId;
        try {
            AiAuditRunEntity initial = lifecycle.activeRun(auditId);
            AuditRequestSpec request = objectMapper.readValue(
                    initial.getRequestJson(), AuditRequestSpec.class);

            JsonNode profile = prepare(auditId, traceId, request);
            JsonNode scanCheckpoint = scan(auditId, traceId, request, profile);
            InvestigatorOutcome investigation = investigate(
                    auditId, traceId, request, profile, scanCheckpoint);
            ObjectNode triageResult = triageStage(
                    auditId, profile, scanCheckpoint, investigation);

            AiAuditRunEntity run = lifecycle.activeRun(auditId);
            run.saveIntermediateResult(triageResult.toString());
            runs.save(run);

            List<AiApprovalRequestEntity> requested = "sandbox".equals(request.verificationLevel())
                    ? approvals.ensureRequests(run, triageResult)
                    : List.of();
            if (!requested.isEmpty() && !approvals.allDecided(auditId)) {
                pauseForApproval(run, triageResult, requested);
                return;
            }

            ObjectNode verified = applyApprovalDecisions(auditId, triageResult);
            ObjectNode report = report(auditId, verified);
            AiAuditRunEntity active = lifecycle.activeRun(auditId);
            Instant completedAt = Instant.now();
            active.complete(report.toString(), completedAt);
            runs.saveAndFlush(active);
            metrics.auditCompleted(Duration.between(active.getCreatedAt(), completedAt));
            events.publish(
                    auditId,
                    "audit.completed",
                    AuditStage.COMPLETED,
                    "Day 5 audit completed",
                    Map.of(
                            "status", "COMPLETED",
                            "finding_count", report.path("findings").size(),
                            "verification_count", report.path("verifications").size()));
        }
        catch (AuditCanceledException canceled) {
            lifecycle.failRunningStep(auditId, "Canceled by user");
        }
        catch (Exception failure) {
            lifecycle.failRunningStep(auditId, safeMessage(failure));
            runs.findById(auditId).ifPresent(run -> {
                if (!run.isCancelRequested()) {
                    AuditStage failureStage = run.getCurrentStage();
                    run.fail("WORKFLOW_FAILED", safeMessage(failure), Instant.now());
                    runs.save(run);
                    metrics.auditFailed(failureStage);
                    events.publish(
                            auditId,
                            "audit.failed",
                            AuditStage.FAILED,
                            "Agent audit failed",
                            Map.of("error_code", "WORKFLOW_FAILED"));
                }
            });
        }
    }

    private JsonNode prepare(String auditId, String traceId, AuditRequestSpec request) {
        var checkpoint = checkpoints.read(auditId, AuditStage.PREPARING);
        if (checkpoint.isPresent()) {
            return checkpoint.get().path("repository_profile");
        }
        AiAuditStepEntity step = lifecycle.start(
                auditId, AuditStage.PREPARING, request.projectId());
        ObjectNode arguments = objectMapper.createObjectNode();
        addStrings(arguments, "target_paths", request.targetPaths());
        JsonNode profile = callWorkflowTool(
                auditId,
                traceId,
                request.projectId(),
                "prepare:profile",
                SecurityToolName.PROFILE_REPOSITORY,
                arguments);
        lifecycle.complete(step, "Repository profile completed", checkpoint("repository_profile", profile));
        return profile;
    }

    private JsonNode scan(
            String auditId, String traceId, AuditRequestSpec request, JsonNode profile) {
        var stored = checkpoints.read(auditId, AuditStage.SCANNING);
        if (stored.isPresent()) {
            return stored.get();
        }
        AiAuditStepEntity step = lifecycle.start(auditId, AuditStage.SCANNING, profile.toString());
        ObjectNode arguments = objectMapper.createObjectNode();
        addStrings(arguments, "scanners", scanners(request));
        addStrings(arguments, "target_paths", request.targetPaths());
        arguments.put("timeout_seconds", 120);
        JsonNode scanJob = callWorkflowTool(
                auditId,
                traceId,
                request.projectId(),
                "scan:start",
                SecurityToolName.START_SECURITY_SCAN,
                arguments);
        String jobId = requiredText(scanJob, "job_id");
        scanJob = pollScan(auditId, traceId, request.projectId(), jobId);
        JsonNode scanFindings = readFindings(auditId, traceId, request.projectId(), jobId);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("job_id", jobId);
        result.set("scan_job", scanJob);
        result.set("scanner_findings", scanFindings);
        lifecycle.complete(
                step,
                "Security scan reached " + scanJob.path("status").asText("unknown"),
                result.toString());
        return result;
    }

    private InvestigatorOutcome investigate(
            String auditId,
            String traceId,
            AuditRequestSpec request,
            JsonNode profile,
            JsonNode scanCheckpoint) {
        var stored = checkpoints.read(auditId, AuditStage.INVESTIGATING);
        if (stored.isPresent()) {
            return objectMapper.convertValue(stored.get(), InvestigatorOutcome.class);
        }
        AiAuditStepEntity step = lifecycle.start(
                auditId, AuditStage.INVESTIGATING, scanCheckpoint.toString());
        var budgets = request.budgets();
        InvestigatorOutcome outcome = investigator.investigate(new InvestigatorContext(
                auditId,
                traceId,
                request.projectId(),
                profile,
                scanCheckpoint.path("scan_job"),
                scanCheckpoint.path("scanner_findings"),
                request.targetVulnerabilities(),
                new InvestigatorBudget(
                        budgets.maxIterations(),
                        budgets.maxInputTokens(),
                        Duration.ofSeconds(budgets.maxDurationSeconds())),
                () -> lifecycle.isCanceled(auditId)));
        metrics.investigation(outcome);
        lifecycle.complete(
                step,
                "Investigator completed with " + outcome.result().findings().size()
                        + " structured findings",
                write(outcome));
        return outcome;
    }

    private ObjectNode triageStage(
            String auditId,
            JsonNode profile,
            JsonNode scanCheckpoint,
            InvestigatorOutcome investigation) {
        var stored = checkpoints.read(auditId, AuditStage.TRIAGING);
        if (stored.isPresent()) {
            return requireObject(stored.get(), "TRIAGING");
        }
        AiAuditStepEntity step = lifecycle.start(auditId, AuditStage.TRIAGING, write(investigation));
        ObjectNode result = triage(
                profile,
                scanCheckpoint.path("scan_job"),
                scanCheckpoint.path("scanner_findings"),
                investigation);
        lifecycle.complete(
                step,
                "Triage produced " + result.path("findings").size() + " deduplicated findings",
                result.toString());
        return result;
    }

    private void pauseForApproval(
            AiAuditRunEntity run,
            ObjectNode triageResult,
            List<AiApprovalRequestEntity> requested) {
        if (!checkpoints.completed(run.getId(), AuditStage.WAITING_APPROVAL)) {
            AiAuditStepEntity step = lifecycle.start(
                    run.getId(), AuditStage.WAITING_APPROVAL, triageResult.toString());
            ObjectNode state = objectMapper.createObjectNode();
            state.put("approval_count", requested.size());
            ArrayNode ids = state.putArray("approval_ids");
            requested.forEach(approval -> ids.add(approval.getId()));
            lifecycle.complete(
                    step,
                    "Workflow paused for " + requested.size() + " verification decisions",
                    state.toString());
        }
        AiAuditRunEntity active = lifecycle.activeRun(run.getId());
        active.waitForApproval(triageResult.toString());
        runs.saveAndFlush(active);
    }

    private ObjectNode applyApprovalDecisions(String auditId, ObjectNode triageResult) {
        List<AiApprovalRequestEntity> entities = approvals.entities(auditId);
        boolean requiresVerification = entities.stream()
                .anyMatch(value -> value.getStatus() == ApprovalStatus.APPROVED);
        ObjectNode result;
        var stored = checkpoints.read(auditId, AuditStage.VERIFYING);
        if (stored.isPresent()) {
            result = requireObject(stored.get(), "VERIFYING");
        }
        else if (requiresVerification) {
            AiAuditStepEntity step = lifecycle.start(
                    auditId, AuditStage.VERIFYING, triageResult.toString());
            AiAuditRunEntity run = lifecycle.activeRun(auditId);
            for (AiApprovalRequestEntity approval : entities) {
                if (approval.getStatus() == ApprovalStatus.APPROVED) {
                    verificationAgent.verify(run, approval);
                    lifecycle.activeRun(auditId);
                }
            }
            result = mergeVerificationResults(triageResult, approvals.entities(auditId));
            lifecycle.complete(
                    step,
                    "Verification decisions were applied",
                    result.toString());
        }
        else {
            result = mergeVerificationResults(triageResult, entities);
        }
        return result;
    }

    private ObjectNode report(String auditId, ObjectNode verified) {
        var stored = checkpoints.read(auditId, AuditStage.REPORTING);
        if (stored.isPresent()) {
            return requireObject(stored.get(), "REPORTING");
        }
        AiAuditStepEntity step = lifecycle.start(auditId, AuditStage.REPORTING, verified.toString());
        ObjectNode report = verified.deepCopy();
        report.put("report_status", "completed");
        report.put("generated_at", Instant.now().toString());
        lifecycle.complete(
                step,
                "Structured report completed",
                report.toString());
        return report;
    }

    private ObjectNode mergeVerificationResults(
            ObjectNode triageResult, List<AiApprovalRequestEntity> entities) {
        ObjectNode result = triageResult.deepCopy();
        ArrayNode verifications = result.putArray("verifications");
        Map<String, ObjectNode> byFinding = new LinkedHashMap<>();
        JsonNode findings = result.path("findings");
        if (findings.isArray()) {
            for (JsonNode finding : findings) {
                if (finding instanceof ObjectNode object) {
                    byFinding.put(object.path("finding_id").asText(""), object);
                }
            }
        }
        int approved = 0;
        int rejected = 0;
        int expired = 0;
        for (AiApprovalRequestEntity approval : entities) {
            ObjectNode value;
            if (approval.getResultJson() != null) {
                value = requireObject(read(approval.getResultJson()), "approval result");
                approved++;
            }
            else {
                value = objectMapper.createObjectNode();
                value.put("approval_id", approval.getId());
                value.put("finding_id", approval.getFindingId());
                value.put("verdict", approval.getStatus() == ApprovalStatus.REJECTED
                        ? "unverified"
                        : "inconclusive");
                value.put("decision", approval.getStatus().name().toLowerCase());
                if (approval.getStatus() == ApprovalStatus.REJECTED) {
                    rejected++;
                }
                else if (approval.getStatus() == ApprovalStatus.EXPIRED) {
                    expired++;
                }
            }
            verifications.add(value);
            ObjectNode finding = byFinding.get(approval.getFindingId());
            if (finding != null) {
                finding.set("verification", value.deepCopy());
            }
        }
        ObjectNode summary = result.putObject("approval_summary");
        summary.put("total", entities.size());
        summary.put("verified", approved);
        summary.put("rejected", rejected);
        summary.put("expired", expired);
        return result;
    }

    private JsonNode pollScan(String auditId, String traceId, String projectId, String jobId) {
        Instant deadline = Instant.now().plus(properties.getScanPollTimeout());
        int poll = 0;
        JsonNode job = objectMapper.createObjectNode().put("status", "queued");
        while (!TERMINAL_SCAN_STATES.contains(job.path("status").asText())) {
            lifecycle.activeRun(auditId);
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Security scan polling exceeded workflow timeout");
            }
            sleep(properties.getScanPollInterval());
            job = callWorkflowTool(
                    auditId,
                    traceId,
                    projectId,
                    "scan:poll:" + poll++,
                    SecurityToolName.GET_SCAN_JOB,
                    objectMapper.createObjectNode().put("job_id", jobId));
        }
        return job;
    }

    private JsonNode readFindings(String auditId, String traceId, String projectId, String jobId) {
        ArrayNode items = objectMapper.createArrayNode();
        String cursor = null;
        String status = "unknown";
        for (int page = 0; page < properties.getMaxFindingPages(); page++) {
            lifecycle.activeRun(auditId);
            ObjectNode arguments = objectMapper.createObjectNode()
                    .put("job_id", jobId)
                    .put("limit", 100);
            if (cursor != null) {
                arguments.put("cursor", cursor);
            }
            JsonNode response = callWorkflowTool(
                    auditId,
                    traceId,
                    projectId,
                    "scan:findings:" + page,
                    SecurityToolName.GET_SCAN_FINDINGS,
                    arguments);
            status = response.path("status").asText(status);
            if (response.path("items").isArray()) {
                response.path("items").forEach(items::add);
            }
            cursor = response.path("next_cursor").isTextual()
                    ? response.path("next_cursor").asText()
                    : null;
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("job_id", jobId);
        result.put("status", status);
        result.set("items", items);
        result.put("total_matching", items.size());
        return result;
    }

    private JsonNode callWorkflowTool(
            String auditId,
            String traceId,
            String projectId,
            String requestSuffix,
            SecurityToolName tool,
            JsonNode arguments) {
        lifecycle.activeRun(auditId);
        permissionPolicy.requireAllowed(AgentRole.WORKFLOW, tool);
        String requestId = boundedRequestId(auditId + ":" + requestSuffix);
        events.publish(
                auditId,
                "tool.started",
                lifecycle.activeRun(auditId).getCurrentStage(),
                "Workflow called " + tool.wireName(),
                Map.of("tool", tool.wireName(), "request_id", requestId));
        McpToolResponse<JsonNode> response = gateway.call(new McpToolRequest(
                tool,
                new McpCallContext(requestId, traceId, auditId, projectId),
                arguments));
        lifecycle.activeRun(auditId);
        if (!response.ok()) {
            events.publish(
                    auditId,
                    "tool.failed",
                    lifecycle.activeRun(auditId).getCurrentStage(),
                    tool.wireName() + " failed",
                    Map.of("tool", tool.wireName(), "error_code", response.error().code().name()));
            throw new IllegalStateException(
                    tool.wireName() + " failed with " + response.error().code().name());
        }
        events.publish(
                auditId,
                "tool.completed",
                lifecycle.activeRun(auditId).getCurrentStage(),
                tool.wireName() + " completed",
                Map.of("tool", tool.wireName(), "request_id", requestId));
        return response.data();
    }

    private ObjectNode triage(
            JsonNode profile,
            JsonNode scanJob,
            JsonNode scanFindings,
            InvestigatorOutcome investigation) {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("repository_profile", profile);
        result.set("scan_job", scanJob);
        result.set("investigation", objectMapper.valueToTree(investigation));
        LinkedHashMap<String, ObjectNode> merged = new LinkedHashMap<>();
        JsonNode scannerItems = scanFindings.path("items");
        if (scannerItems.isArray()) {
            scannerItems.forEach(item -> mergeFinding(merged, item));
        }
        investigation.result().findings().forEach(
                finding -> mergeFinding(merged, objectMapper.valueToTree(finding)));
        ArrayNode findings = result.putArray("findings");
        merged.values().forEach(findings::add);
        ObjectNode stats = result.putObject("stats");
        stats.put("scanner_findings", scannerItems.isArray() ? scannerItems.size() : 0);
        stats.put("agent_findings", investigation.result().findings().size());
        stats.put("deduplicated_findings", merged.size());
        stats.put("agent_iterations", investigation.iterations());
        stats.put("estimated_tokens", investigation.estimatedTokens());
        stats.put("termination_reason", investigation.terminationReason());
        stats.put("model_runtime", investigation.modelRuntime());
        return result;
    }

    private void mergeFinding(Map<String, ObjectNode> target, JsonNode value) {
        ObjectNode finding = value instanceof ObjectNode object
                ? object.deepCopy()
                : objectMapper.createObjectNode();
        String key = finding.path("file_path").asText("") + "|"
                + finding.path("line_start").asInt(0) + "|"
                + finding.path("category").asText("") + "|"
                + finding.path("title").asText("");
        if (finding.path("finding_id").asText("").isBlank()) {
            finding.put("finding_id", sha256(key));
        }
        String severity = finding.path("severity").asText("medium");
        boolean needsVerification = finding.path("needs_verification").asBoolean(false)
                || severity.equals("critical")
                || severity.equals("high");
        finding.put("needs_verification", needsVerification);
        ObjectNode existing = target.putIfAbsent(key, finding);
        if (existing != null && needsVerification) {
            existing.put("needs_verification", true);
        }
    }

    private List<String> scanners(AuditRequestSpec request) {
        if (!request.scanners().isEmpty()) {
            if (request.scanners().stream().anyMatch(scanner -> !ALLOWED_SCANNERS.contains(scanner))) {
                throw new IllegalArgumentException("scanners contains an unsupported value");
            }
            return request.scanners();
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String category : request.targetVulnerabilities()) {
            switch (category.toLowerCase()) {
                case "secret" -> selected.add("gitleaks");
                case "dependency" -> selected.add("osv");
                case "authorization", "injection", "logic" -> selected.add("semgrep");
                default -> { }
            }
        }
        if (selected.isEmpty()) {
            selected.add("gitleaks");
        }
        return List.copyOf(selected);
    }

    private String checkpoint(String key, JsonNode value) {
        ObjectNode checkpoint = objectMapper.createObjectNode();
        checkpoint.set(key, value);
        return checkpoint.toString();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Workflow value cannot be serialized", exception);
        }
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Stored workflow JSON is invalid", exception);
        }
    }

    private static ObjectNode requireObject(JsonNode value, String source) {
        if (value instanceof ObjectNode object) {
            return object.deepCopy();
        }
        throw new IllegalStateException(source + " checkpoint must be a JSON object");
    }

    private static void addStrings(ObjectNode node, String key, List<String> values) {
        ArrayNode array = node.putArray(key);
        values.forEach(array::add);
    }

    private static String requiredText(JsonNode node, String key) {
        String value = node.path(key).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("MCP response is missing " + key);
        }
        return value;
    }

    private static String boundedRequestId(String value) {
        return value.substring(0, Math.min(128, value.length()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuditCanceledException("Audit workflow thread was interrupted");
        }
    }

    private String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            value = throwable.getClass().getSimpleName();
        }
        return redactor.redactForLog(value);
    }
}
