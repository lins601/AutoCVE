package com.autocve.agent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.autocve.agent.agent.model.InvestigationFinding;
import com.autocve.agent.agent.model.InvestigationResult;
import com.autocve.agent.agent.model.InvestigatorBudget;
import com.autocve.agent.agent.model.InvestigatorDecision;
import com.autocve.agent.agent.model.InvestigatorDecisionModel;
import com.autocve.agent.agent.model.InvestigatorModelRequest;
import com.autocve.agent.infrastructure.event.AuditEventPublisher;
import com.autocve.agent.mcp.dto.McpToolRequest;
import com.autocve.agent.mcp.dto.McpToolResponse;
import com.autocve.agent.mcp.dto.SecurityToolName;
import com.autocve.agent.mcp.gateway.SecurityToolGateway;
import com.autocve.agent.mcp.gateway.ToolPermissionDeniedException;
import com.autocve.agent.mcp.gateway.ToolPermissionPolicy;
import com.autocve.agent.workflow.AgentWorkflowProperties;
import jakarta.validation.Validation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class InvestigatorAgentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validatesAndReturnsStructuredFinalizeResult() {
        InvestigationResult result = new InvestigationResult(
                "Reviewed authorization flow",
                List.of(new InvestigationFinding(
                        "Missing ownership check",
                        "authorization",
                        "high",
                        0.9,
                        "src/OrderService.java",
                        42,
                        45,
                        List.of("owner id is not compared"),
                        List.of("attacker supplies another order id"),
                        true)),
                List.of("runtime authorization policy"));
        InvestigatorAgent agent = agent(
                request -> InvestigatorDecision.finalize(result, 123),
                new CountingGateway());

        InvestigatorOutcome outcome = agent.investigate(context());

        assertEquals("finalize", outcome.terminationReason());
        assertEquals(1, outcome.result().findings().size());
        assertEquals(123, outcome.estimatedTokens());
    }

    @Test
    void rejectsWorkflowOnlyToolsRequestedByTheModel() {
        InvestigatorAgent agent = agent(
                request -> InvestigatorDecision.tool(
                        SecurityToolName.START_SECURITY_SCAN,
                        objectMapper.createObjectNode().putArray("scanners").add("gitleaks"),
                        10),
                new CountingGateway());

        assertThrows(ToolPermissionDeniedException.class, () -> agent.investigate(context()));
    }

    @Test
    void stopsBeforeExecutingARepeatedToolCallTwice() {
        CountingGateway gateway = new CountingGateway();
        InvestigatorAgent agent = agent(
                request -> InvestigatorDecision.tool(
                        SecurityToolName.SEARCH_CODE,
                        objectMapper.createObjectNode().put("query", "authorization"),
                        5),
                gateway);

        InvestigatorOutcome outcome = agent.investigate(context());

        assertEquals("repeated_tool_call", outcome.terminationReason());
        assertEquals(1, gateway.calls.get());
    }

    private InvestigatorAgent agent(
            DecisionFunction decision,
            SecurityToolGateway gateway) {
        InvestigatorDecisionModel model = new InvestigatorDecisionModel() {
            @Override
            public InvestigatorDecision decide(InvestigatorModelRequest request) {
                return decision.apply(request);
            }

            @Override
            public String runtimeName() {
                return "test-model";
            }
        };
        AuditEventPublisher events = (auditId, type, stage, summary, data) -> null;
        return new InvestigatorAgent(
                model,
                gateway,
                new ToolPermissionPolicy(),
                events,
                Validation.buildDefaultValidatorFactory().getValidator(),
                objectMapper,
                new AgentWorkflowProperties());
    }

    private InvestigatorContext context() {
        JsonNode findings = objectMapper.readTree("""
                {"items":[{"title":"Secret","category":"secret","severity":"high",
                "file_path":"app.yml","line_start":2,"evidence":["candidate"]}]}
                """);
        return new InvestigatorContext(
                "audit-1",
                "trace-1",
                "project-1",
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                findings,
                List.of("authorization"),
                new InvestigatorBudget(5, 10_000, Duration.ofSeconds(10)),
                () -> false);
    }

    @FunctionalInterface
    private interface DecisionFunction {
        InvestigatorDecision apply(InvestigatorModelRequest request);
    }

    private static final class CountingGateway implements SecurityToolGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Set<String> discoverTools() {
            return Set.of();
        }

        @Override
        public McpToolResponse<JsonNode> call(McpToolRequest request) {
            calls.incrementAndGet();
            return McpToolResponse.success(
                    request.context().traceId(),
                    request.context().requestId(),
                    new ObjectMapper().createObjectNode().put("result_count", 1),
                    List.of(),
                    List.of());
        }
    }
}
