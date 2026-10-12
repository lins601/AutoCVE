package com.autocve.agent.agent;

import com.autocve.agent.agent.model.VerificationPlan;
import com.autocve.agent.agent.model.VerificationPlanModel;
import com.autocve.agent.application.ApprovalProperties;
import com.autocve.agent.application.ApprovalService;
import com.autocve.agent.application.ApprovalTokenService;
import com.autocve.agent.infrastructure.event.AuditEventPublisher;
import com.autocve.agent.infrastructure.persistence.AiApprovalRequestEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import com.autocve.agent.mcp.dto.McpCallContext;
import com.autocve.agent.mcp.dto.McpToolRequest;
import com.autocve.agent.mcp.dto.McpToolResponse;
import com.autocve.agent.mcp.dto.SecurityToolName;
import com.autocve.agent.mcp.gateway.AgentRole;
import com.autocve.agent.mcp.gateway.SecurityToolGateway;
import com.autocve.agent.mcp.gateway.ToolPermissionPolicy;
import com.autocve.agent.workflow.AuditStage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class VerificationAgent {

    private final ApprovalService approvals;
    private final ApprovalTokenService tokens;
    private final VerificationPlanModel plans;
    private final ToolPermissionPolicy permissions;
    private final SecurityToolGateway gateway;
    private final AuditEventPublisher events;
    private final ApprovalProperties properties;
    private final ObjectMapper objectMapper;

    public VerificationAgent(
            ApprovalService approvals,
            ApprovalTokenService tokens,
            VerificationPlanModel plans,
            ToolPermissionPolicy permissions,
            SecurityToolGateway gateway,
            AuditEventPublisher events,
            ApprovalProperties properties,
            ObjectMapper objectMapper) {
        this.approvals = approvals;
        this.tokens = tokens;
        this.plans = plans;
        this.permissions = permissions;
        this.gateway = gateway;
        this.events = events;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public VerificationOutcome verify(AiAuditRunEntity run, AiApprovalRequestEntity requested) {
        JsonNode storedFinding = read(requested.getRequestPayload());
        VerificationPlan plan = plans.plan(storedFinding);
        ObjectNode finding = canonicalFinding(storedFinding);
        String token = tokens.issue(run, requested);
        AiApprovalRequestEntity claimed = approvals.claimApproved(run.getId(), requested.getId());
        permissions.requireAllowed(AgentRole.VERIFICATION, SecurityToolName.VERIFY_VULNERABILITY);

        String requestId = bounded(run.getId() + ":verify:" + claimed.getId());
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("approval_id", claimed.getId());
        arguments.put("approval_token", token);
        arguments.put("action_digest", claimed.getActionDigest());
        arguments.set("finding", finding);
        arguments.put("verification_mode", plan.verificationMode());
        arguments.put("timeout_seconds", properties.getVerificationTimeout().toSeconds());
        events.publish(
                run.getId(),
                "tool.started",
                AuditStage.VERIFYING,
                "Verification Agent called verify_vulnerability",
                Map.of(
                        "approval_id", claimed.getId(),
                        "finding_id", claimed.getFindingId(),
                        "request_id", requestId));

        McpToolResponse<JsonNode> response = gateway.call(new McpToolRequest(
                SecurityToolName.VERIFY_VULNERABILITY,
                new McpCallContext(
                        requestId,
                        "trace:" + run.getId(),
                        run.getId(),
                        run.getProjectId()),
                arguments));
        VerificationOutcome outcome = response.ok()
                ? success(claimed, response, plan)
                : failure(claimed, response, plan);
        approvals.storeResult(run.getId(), claimed.getId(), objectMapper.valueToTree(outcome));
        events.publish(
                run.getId(),
                response.ok() ? "tool.completed" : "tool.failed",
                AuditStage.VERIFYING,
                "Verification completed with " + outcome.verdict(),
                Map.of(
                        "approval_id", claimed.getId(),
                        "finding_id", claimed.getFindingId(),
                        "verdict", outcome.verdict()));
        return outcome;
    }

    private VerificationOutcome success(
            AiApprovalRequestEntity approval,
            McpToolResponse<JsonNode> response,
            VerificationPlan plan) {
        JsonNode data = response.data();
        List<String> evidence = new ArrayList<>();
        if (data.path("evidence").isArray()) {
            data.path("evidence").forEach(value -> evidence.add(value.asText("")));
        }
        return new VerificationOutcome(
                approval.getId(),
                approval.getFindingId(),
                data.path("verdict").asText("inconclusive"),
                evidence,
                data.path("execution"),
                response.artifacts(),
                plan,
                plans.runtimeName(),
                null);
    }

    private VerificationOutcome failure(
            AiApprovalRequestEntity approval,
            McpToolResponse<JsonNode> response,
            VerificationPlan plan) {
        String code = response.error() == null ? "INTERNAL_ERROR" : response.error().code().name();
        String message = response.error() == null
                ? "Verification dependency failed"
                : response.error().message();
        return new VerificationOutcome(
                approval.getId(),
                approval.getFindingId(),
                "inconclusive",
                List.of(message),
                objectMapper.createObjectNode()
                        .put("success", false)
                        .put("timed_out", code.equals("TOOL_TIMEOUT")),
                List.of(),
                plan,
                plans.runtimeName(),
                code);
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Stored approval finding is invalid JSON", exception);
        }
    }

    private ObjectNode canonicalFinding(JsonNode source) {
        String findingId = requiredText(source, "finding_id", 128);
        String title = requiredText(source, "title", 500);
        String category = requiredText(source, "category", 64);
        String filePath = requiredText(source, "file_path", 4096);
        int lineStart = source.path("line_start").asInt(0);
        JsonNode evidence = source.path("evidence");
        if (lineStart < 1 || !evidence.isArray() || evidence.isEmpty() || evidence.size() > 20) {
            throw new IllegalStateException("Approved finding violates the verification contract");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("finding_id", findingId);
        result.put("title", title);
        result.put("category", category);
        result.put("file_path", filePath);
        result.put("line_start", lineStart);
        var targetEvidence = result.putArray("evidence");
        evidence.forEach(value -> {
            String item = value.asText("");
            if (item.isBlank() || item.length() > 4000) {
                throw new IllegalStateException(
                        "Approved finding evidence violates the verification contract");
            }
            targetEvidence.add(item);
        });
        return result;
    }

    private static String requiredText(JsonNode source, String field, int maxLength) {
        String value = source.path(field).asText("");
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalStateException(
                    "Approved finding field violates the verification contract: " + field);
        }
        return value;
    }

    private static String bounded(String value) {
        return value.substring(0, Math.min(128, value.length()));
    }
}
