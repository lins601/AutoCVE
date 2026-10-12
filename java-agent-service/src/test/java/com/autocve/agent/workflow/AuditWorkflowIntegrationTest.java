package com.autocve.agent.workflow;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autocve.agent.AutoCveAgentApplication;
import com.autocve.agent.application.AuditBudgets;
import com.autocve.agent.application.AuditCommandService;
import com.autocve.agent.application.AuditQueryService;
import com.autocve.agent.application.AuditRequestSpec;
import com.autocve.agent.application.AuditConflictException;
import com.autocve.agent.domain.ApprovalStatus;
import com.autocve.agent.domain.AuditRunStatus;
import com.autocve.agent.infrastructure.event.InMemoryAuditEventBroker;
import com.autocve.agent.infrastructure.persistence.AiAuditStepRepository;
import com.autocve.agent.mcp.dto.McpToolRequest;
import com.autocve.agent.mcp.dto.McpToolResponse;
import com.autocve.agent.mcp.dto.SecurityToolName;
import com.autocve.agent.mcp.gateway.SecurityToolGateway;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        classes = AutoCveAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.ai.mcp.client.enabled=false",
            "spring.ai.model.chat=none",
            "spring.datasource.url=jdbc:h2:mem:day4-workflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.flyway.enabled=true",
            "autocve.agent.workflow.llm-enabled=false",
            "autocve.agent.workflow.scan-poll-interval=1ms",
            "autocve.agent.workflow.scan-poll-timeout=5s"
        })
@Import(AuditWorkflowIntegrationTest.FakeGatewayConfiguration.class)
class AuditWorkflowIntegrationTest {

    @Autowired private AuditCommandService commands;
    @Autowired private AuditQueryService queries;
    @Autowired private FakeSecurityToolGateway gateway;
    @Autowired private AiAuditStepRepository steps;
    @Autowired private InMemoryAuditEventBroker events;
    @Autowired private AuditWorkflow workflow;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    @Test
    void runsFromPreparingThroughReportingAndReplaysEvents() {
        var created = commands.create(request(), "workflow-idempotency-1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var snapshot = queries.get(created.id());
            assertEquals(AuditRunStatus.COMPLETED, snapshot.status());
            assertEquals(AuditStage.COMPLETED, snapshot.currentStage());
        });

        var completed = queries.get(created.id());
        assertEquals(5, steps.countByAuditId(created.id()));
        assertEquals(1, completed.result().path("findings").size());
        assertEquals("deterministic-fallback", completed.result()
                .path("stats").path("model_runtime").asText());
        assertTrue(events.events(created.id(), null).stream()
                .anyMatch(event -> event.type().equals("agent.iteration.started")));
        assertTrue(events.events(created.id(), null).stream()
                .anyMatch(event -> event.type().equals("audit.completed")));

        var deduplicated = commands.create(request(), "workflow-idempotency-1");
        assertEquals(created.id(), deduplicated.id());
    }

    @Test
    void cancellationStopsAllSubsequentModelAndMcpCalls() throws Exception {
        gateway.blockProfile();
        var created = commands.create(request(), "workflow-cancel-1");
        assertTrue(gateway.profileEntered.await(5, TimeUnit.SECONDS));

        var canceled = commands.cancel(created.id());
        gateway.releaseProfile();

        assertEquals(AuditRunStatus.CANCELED, canceled.status());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(AuditRunStatus.CANCELED, queries.get(created.id()).status()));
        assertEquals(1, gateway.totalCalls.get());
        assertTrue(events.events(created.id(), null).stream()
                .anyMatch(event -> event.type().equals("audit.canceled")));
    }

    @Test
    void approvalBindsOneFindingAndRunsVerificationExactlyOnce() {
        var created = commands.create(sandboxRequest("project-day5-approved"), "workflow-day5-approve");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(
                        AuditRunStatus.WAITING_APPROVAL,
                        queries.get(created.id()).status()));
        var approval = commands.listApprovals(created.id()).getFirst();
        assertEquals(ApprovalStatus.PENDING, approval.status());
        assertEquals(0, gateway.verifyCalls.get());

        int callsAtCheckpoint = gateway.totalCalls.get();
        workflow.run(created.id());
        assertEquals(callsAtCheckpoint, gateway.totalCalls.get());
        assertEquals(AuditRunStatus.WAITING_APPROVAL, queries.get(created.id()).status());

        assertThrows(
                AuditConflictException.class,
                () -> commands.approve(
                        created.id(),
                        approval.id(),
                        "0".repeat(64),
                        "security-reviewer"));
        assertEquals(0, gateway.verifyCalls.get());

        commands.approve(
                created.id(),
                approval.id(),
                approval.actionDigest(),
                "security-reviewer");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(AuditRunStatus.COMPLETED, queries.get(created.id()).status());
            assertEquals(
                    ApprovalStatus.CONSUMED,
                    commands.listApprovals(created.id()).getFirst().status());
        });

        assertEquals(1, gateway.verifyCalls.get());
        McpToolRequest request = gateway.lastVerification.get();
        assertEquals(approval.id(), request.arguments().path("approval_id").asText());
        assertEquals(
                approval.findingId(),
                request.arguments().path("finding").path("finding_id").asText());
        assertEquals(
                "likely_vulnerable",
                queries.get(created.id()).result()
                        .path("verifications").get(0).path("verdict").asText());
        assertThrows(
                AuditConflictException.class,
                () -> commands.approve(
                        created.id(),
                        approval.id(),
                        approval.actionDigest(),
                        "security-reviewer"));
        assertEquals(1, gateway.verifyCalls.get());
    }

    @Test
    void rejectionCompletesWithAnExplicitUnverifiedResult() {
        var created = commands.create(sandboxRequest("project-day5-rejected"), "workflow-day5-reject");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(
                        AuditRunStatus.WAITING_APPROVAL,
                        queries.get(created.id()).status()));
        var approval = commands.listApprovals(created.id()).getFirst();

        commands.reject(
                created.id(),
                approval.id(),
                approval.actionDigest(),
                "security-reviewer");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AuditRunStatus.COMPLETED, queries.get(created.id()).status()));
        assertEquals(0, gateway.verifyCalls.get());
        assertEquals(
                "unverified",
                queries.get(created.id()).result()
                        .path("verifications").get(0).path("verdict").asText());
        assertEquals(
                ApprovalStatus.REJECTED,
                commands.listApprovals(created.id()).getFirst().status());
    }

    @Test
    void failedStageRetryContinuesAfterTheLatestCompletedCheckpoint() {
        gateway.failNextScanStart();
        var created = commands.create(
                requestForProject("project-day5-retry"), "workflow-day5-retry");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var failed = queries.get(created.id());
            assertEquals(AuditRunStatus.FAILED, failed.status());
            assertEquals(AuditStage.SCANNING, failed.failureStage());
        });
        assertEquals(1, gateway.profileCalls.get());
        assertEquals(1, gateway.scanStartCalls.get());

        commands.retry(created.id());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(AuditRunStatus.COMPLETED, queries.get(created.id()).status()));
        assertEquals(1, gateway.profileCalls.get());
        assertEquals(2, gateway.scanStartCalls.get());
    }

    private static AuditRequestSpec request() {
        return requestForProject("project-day4");
    }

    private static AuditRequestSpec requestForProject(String projectId) {
        return new AuditRequestSpec(
                projectId,
                List.of("."),
                List.of("secret"),
                "none",
                "default",
                List.of("gitleaks"),
                new AuditBudgets(4, 10_000, 30));
    }

    private static AuditRequestSpec sandboxRequest(String projectId) {
        return new AuditRequestSpec(
                projectId,
                List.of("."),
                List.of("secret"),
                "sandbox",
                "default",
                List.of("gitleaks"),
                new AuditBudgets(4, 10_000, 30));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeGatewayConfiguration {
        @Bean
        @Primary
        FakeSecurityToolGateway fakeSecurityToolGateway() {
            return new FakeSecurityToolGateway();
        }
    }

    static final class FakeSecurityToolGateway implements SecurityToolGateway {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final AtomicInteger totalCalls = new AtomicInteger();
        private final AtomicInteger verifyCalls = new AtomicInteger();
        private final AtomicInteger profileCalls = new AtomicInteger();
        private final AtomicInteger scanStartCalls = new AtomicInteger();
        private final AtomicReference<McpToolRequest> lastVerification = new AtomicReference<>();
        private final AtomicBoolean failScanStartOnce = new AtomicBoolean();
        private final AtomicBoolean blockProfile = new AtomicBoolean();
        private CountDownLatch profileEntered = new CountDownLatch(1);
        private CountDownLatch profileRelease = new CountDownLatch(1);

        void reset() {
            totalCalls.set(0);
            verifyCalls.set(0);
            profileCalls.set(0);
            scanStartCalls.set(0);
            lastVerification.set(null);
            failScanStartOnce.set(false);
            blockProfile.set(false);
            profileEntered = new CountDownLatch(1);
            profileRelease = new CountDownLatch(1);
        }

        void blockProfile() {
            blockProfile.set(true);
        }

        void releaseProfile() {
            profileRelease.countDown();
        }

        void failNextScanStart() {
            failScanStartOnce.set(true);
        }

        @Override
        public Set<String> discoverTools() {
            return Set.of(
                    "profile_repository",
                    "search_code",
                    "start_security_scan",
                    "get_scan_job",
                    "get_scan_findings",
                    "verify_vulnerability");
        }

        @Override
        public McpToolResponse<JsonNode> call(McpToolRequest request) {
            totalCalls.incrementAndGet();
            if (request.tool() == SecurityToolName.PROFILE_REPOSITORY) {
                profileCalls.incrementAndGet();
            }
            if (request.tool() == SecurityToolName.START_SECURITY_SCAN) {
                scanStartCalls.incrementAndGet();
                if (failScanStartOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("simulated scanner dependency failure");
                }
            }
            if (request.tool() == SecurityToolName.VERIFY_VULNERABILITY) {
                verifyCalls.incrementAndGet();
                lastVerification.set(request);
            }
            if (request.tool() == SecurityToolName.PROFILE_REPOSITORY && blockProfile.get()) {
                profileEntered.countDown();
                try {
                    profileRelease.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            JsonNode data = switch (request.tool()) {
                case PROFILE_REPOSITORY -> objectMapper.createObjectNode()
                        .put("file_count", 12)
                        .putArray("languages").add("java");
                case START_SECURITY_SCAN -> objectMapper.createObjectNode()
                        .put("job_id", "job-day4")
                        .put("status", "queued");
                case GET_SCAN_JOB -> objectMapper.createObjectNode()
                        .put("job_id", "job-day4")
                        .put("status", "completed");
                case GET_SCAN_FINDINGS -> findings();
                case SEARCH_CODE -> objectMapper.createObjectNode()
                        .put("result_count", 1)
                        .putArray("matches");
                case VERIFY_VULNERABILITY -> objectMapper.createObjectNode()
                        .put("verdict", "likely_vulnerable")
                        .set("execution", objectMapper.createObjectNode()
                                .put("success", true)
                                .put("timed_out", false));
                default -> throw new IllegalArgumentException("Unexpected tool in Day 4 test");
            };
            if (request.tool() == SecurityToolName.VERIFY_VULNERABILITY) {
                ((ObjectNode) data).putArray("evidence").add("bounded sandbox marker matched");
            }
            return McpToolResponse.success(
                    request.context().traceId(),
                    request.context().requestId(),
                    data,
                    List.of(),
                    List.of());
        }

        private ObjectNode findings() {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("job_id", "job-day4");
            response.put("status", "completed");
            response.putNull("next_cursor");
            ObjectNode finding = objectMapper.createObjectNode()
                    .put("finding_id", "finding-1")
                    .put("title", "Credential candidate")
                    .put("category", "secret")
                    .put("severity", "high")
                    .put("file_path", "application.yml")
                    .put("line_start", 4);
            finding.putArray("evidence").add("redacted scanner evidence");
            response.putArray("items").add(finding);
            response.put("total_matching", 1);
            return response;
        }
    }
}
