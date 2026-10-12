package com.autocve.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autocve.agent.mcp.dto.McpErrorCode;
import com.autocve.agent.mcp.dto.SecurityToolName;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpContractSchemaTest {

    private static final String CONTRACT_RELATIVE_PATH =
            "contracts/mcp/security-tools.schema.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void javaTypesMatchCanonicalJsonSchema() throws IOException {
        JsonNode schema = objectMapper.readTree(findContractPath().toFile());

        assertEquals("1.0.0", schema.path("x-autocve-contract-version").asText());
        assertEquals(256 * 1024, schema.path("x-autocve-max-response-bytes").asInt());
        assertEquals(
                Stream.of(SecurityToolName.values())
                        .map(SecurityToolName::wireName)
                        .collect(Collectors.toSet()),
                textSet(schema.path("x-autocve-tool-names")));
        assertEquals(
                Stream.of(McpErrorCode.values()).map(Enum::name).collect(Collectors.toSet()),
                textSet(schema.path("x-autocve-error-codes")));
        assertEquals(
                Set.of("request_id", "trace_id", "audit_id", "project_id"),
                textSet(schema.path("$defs").path("commonRequest").path("required")));

        JsonNode definitions = schema.path("$defs");
        for (Map.Entry<String, JsonNode> tool
                : schema.path("x-autocve-tool-definitions").properties()) {
            assertTrue(
                    definitions.has(tool.getValue().asText()),
                    () -> "Missing schema definition for " + tool.getKey());
        }

        JsonNode verification = definitions.path("verifyVulnerabilityRequest").path("allOf").get(1);
        assertEquals(
                Set.of(
                        "approval_id",
                        "approval_token",
                        "action_digest",
                        "finding",
                        "verification_mode"),
                textSet(verification.path("required")));
        assertEquals(
                "^[a-f0-9]{64}$",
                verification.path("properties").path("action_digest").path("pattern").asText());
        JsonNode finding = definitions.path("findingForVerification");
        assertTrue(finding.path("additionalProperties").isBoolean());
        assertEquals(false, finding.path("additionalProperties").asBoolean());
        assertEquals(
                Set.of("finding_id", "title", "category", "file_path", "line_start", "evidence"),
                textSet(finding.path("required")));
    }

    private static Set<String> textSet(JsonNode arrayNode) {
        Set<String> result = new HashSet<>();
        arrayNode.forEach(node -> result.add(node.asText()));
        return result;
    }

    private static Path findContractPath() {
        String configuredPath = System.getenv("AUTOCVE_MCP_CONTRACT_PATH");
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(configured)) {
                return configured;
            }
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(CONTRACT_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate canonical MCP contract: " + CONTRACT_RELATIVE_PATH);
    }
}
