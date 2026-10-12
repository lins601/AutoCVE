package com.autocve.agent.mcp.gateway;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autocve.agent.AutoCveAgentApplication;
import com.autocve.agent.mcp.dto.McpCallContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        classes = AutoCveAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mcp-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false"
        })
@EnabledIfEnvironmentVariable(named = "MCP_INTEGRATION_URL", matches = ".+")
class JavaPythonMcpIntegrationTest {

    @DynamicPropertySource
    static void mcpProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.ai.mcp.client.streamable-http.connections.autocve-security.url",
                () -> System.getenv("MCP_INTEGRATION_URL"));
    }

    @Autowired
    private SecurityToolGateway gateway;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void javaDiscoversAndCallsPythonReadAndScanTools() throws Exception {
        String projectId = requiredEnvironment("MCP_INTEGRATION_PROJECT_ID");
        String suffix = UUID.randomUUID().toString();
        Set<String> tools = gateway.discoverTools();
        assertTrue(tools.containsAll(Set.of(
                "profile_repository",
                "search_code",
                "start_security_scan",
                "get_scan_job",
                "get_scan_findings")));

        var profile = gateway.profileRepository(
                context("profile-" + suffix, projectId),
                objectMapper.readTree("{\"target_paths\":[\".\"]}"));
        assertTrue(profile.ok());

        var search = gateway.searchCode(
                context("search-" + suffix, projectId),
                objectMapper.readTree("{\"query\":\"authorization permission\",\"top_k\":3}"));
        assertTrue(search.ok());

        String scanner = System.getenv().getOrDefault("MCP_INTEGRATION_SCANNER", "gitleaks");
        ObjectNode startArguments = objectMapper.createObjectNode();
        startArguments.putArray("scanners").add(scanner);
        startArguments.putArray("target_paths").add(".");
        startArguments.put("timeout_seconds", 120);
        var start = gateway.startSecurityScan(
                context("scan-" + suffix, projectId),
                startArguments);
        assertTrue(start.ok());
        String jobId = start.data().path("job_id").asText();
        assertNotNull(jobId);

        String status = "queued";
        for (int attempt = 0; attempt < 120 && Set.of("queued", "running").contains(status); attempt++) {
            Thread.sleep(500);
            ObjectNode pollArguments = objectMapper.createObjectNode().put("job_id", jobId);
            var poll = gateway.getScanJob(
                    context("poll-" + attempt + "-" + suffix, projectId),
                    pollArguments);
            assertTrue(poll.ok());
            status = poll.data().path("status").asText();
        }
        assertTrue(Set.of("completed", "failed").contains(status));

        ObjectNode findingArguments = objectMapper.createObjectNode()
                .put("job_id", jobId)
                .put("limit", 10);
        var findings = gateway.getScanFindings(
                context("findings-" + suffix, projectId),
                findingArguments);
        assertTrue(findings.ok());
        assertTrue(findings.data().path("items").isArray());
    }

    private static McpCallContext context(String requestId, String projectId) {
        return new McpCallContext(requestId, "trace-" + requestId, "audit-day3", projectId);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
