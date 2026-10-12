package com.autocve.agent.mcp.gateway;

import com.autocve.agent.mcp.dto.McpCallContext;
import com.autocve.agent.mcp.dto.McpErrorCode;
import com.autocve.agent.mcp.dto.McpToolError;
import com.autocve.agent.mcp.dto.McpToolRequest;
import com.autocve.agent.mcp.dto.McpToolResponse;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SpringAiSecurityToolGateway implements SecurityToolGateway {

    private final McpSyncClient client;
    private final ObjectMapper objectMapper;
    private final McpToolCallRecorder recorder;
    private final Retry retry;

    public SpringAiSecurityToolGateway(
            List<McpSyncClient> clients,
            ObjectMapper objectMapper,
            McpToolCallRecorder recorder,
            McpGatewayProperties properties) {
        this.client = clients == null || clients.isEmpty() ? null : clients.getFirst();
        this.objectMapper = objectMapper;
        this.recorder = recorder;
        this.retry = Retry.of(
                "autocve-security-mcp",
                RetryConfig.custom()
                        .maxAttempts(properties.maxAttempts())
                        .waitDuration(properties.retryWait())
                        .retryOnException(this::isDependencyFailure)
                        .build());
    }

    @Override
    public Set<String> discoverTools() {
        try {
            return Retry.decorateSupplier(retry, this::listToolNames).get();
        }
        catch (RuntimeException exception) {
            throw new McpDependencyException("MCP tool discovery failed", exception);
        }
    }

    @Override
    public McpToolResponse<JsonNode> call(McpToolRequest request) {
        McpCallContext context = request.context();
        Map<String, Object> arguments = flattenArguments(request);
        AtomicInteger attempts = new AtomicInteger();
        long started = System.nanoTime();
        McpToolResponse<JsonNode> response;

        MDC.put("trace_id", context.traceId());
        MDC.put("request_id", context.requestId());
        try {
            Supplier<McpToolResponse<JsonNode>> supplier = () -> {
                attempts.incrementAndGet();
                return invoke(request.tool().wireName(), arguments);
            };
            response = request.tool() == com.autocve.agent.mcp.dto.SecurityToolName.VERIFY_VULNERABILITY
                    ? supplier.get()
                    : Retry.decorateSupplier(retry, supplier).get();
        }
        catch (RuntimeException exception) {
            response = McpToolResponse.failure(
                    context.traceId(),
                    context.requestId(),
                    new McpToolError(
                            McpErrorCode.DEPENDENCY_UNAVAILABLE,
                            "Python MCP security service is unavailable",
                            true,
                            null));
        }
        finally {
            MDC.remove("trace_id");
            MDC.remove("request_id");
        }

        long durationMs = (System.nanoTime() - started) / 1_000_000;
        recorder.record(new McpToolCallRecord(
                context.auditId(),
                request.tool().wireName(),
                context.requestId(),
                context.traceId(),
                Set.copyOf(arguments.keySet()),
                attempts.get(),
                durationMs,
                response.ok() ? "success" : "failure",
                response.error() == null ? null : response.error().code().name(),
                Instant.now()));
        return response;
    }

    private Set<String> listToolNames() {
        McpSyncClient activeClient = requireClient();
        Set<String> names = new LinkedHashSet<>();
        for (McpSchema.Tool tool : activeClient.listTools().tools()) {
            names.add(tool.name());
        }
        return Set.copyOf(names);
    }

    private McpToolResponse<JsonNode> invoke(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = requireClient().callTool(
                McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
        if (Boolean.TRUE.equals(result.isError())) {
            return McpToolResponse.failure(
                    stringValue(arguments.get("trace_id"), "unknown-trace"),
                    stringValue(arguments.get("request_id"), "unknown-request"),
                    new McpToolError(
                            McpErrorCode.INTERNAL_ERROR,
                            firstText(result, "MCP tool returned a protocol error"),
                            false,
                            null));
        }

        Object structured = result.structuredContent();
        try {
            if (structured != null) {
                JsonNode tree = objectMapper.valueToTree(structured);
                return objectMapper.readValue(
                        tree.toString(),
                        new TypeReference<McpToolResponse<JsonNode>>() {});
            }
            return objectMapper.readValue(
                    firstText(result, "{}"),
                    new TypeReference<McpToolResponse<JsonNode>>() {});
        }
        catch (Exception exception) {
            throw new IllegalStateException("MCP tool returned an invalid response contract", exception);
        }
    }

    private Map<String, Object> flattenArguments(McpToolRequest request) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (request.arguments() != null && request.arguments().isObject()) {
            arguments.putAll(objectMapper.convertValue(
                    request.arguments(),
                    new TypeReference<Map<String, Object>>() {}));
        }
        McpCallContext context = request.context();
        arguments.put("request_id", context.requestId());
        arguments.put("trace_id", context.traceId());
        arguments.put("audit_id", context.auditId());
        arguments.put("project_id", context.projectId());
        return arguments;
    }

    private static String firstText(McpSchema.CallToolResult result, String fallback) {
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text && text.text() != null) {
                return text.text();
            }
        }
        return fallback;
    }

    private boolean isDependencyFailure(Throwable throwable) {
        return !(throwable instanceof IllegalArgumentException);
    }

    private McpSyncClient requireClient() {
        if (client == null) {
            throw new IllegalStateException("No Spring AI MCP client was configured");
        }
        return client;
    }

    private static String stringValue(Object value, String fallback) {
        String normalized = value == null ? "" : String.valueOf(value).trim();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
