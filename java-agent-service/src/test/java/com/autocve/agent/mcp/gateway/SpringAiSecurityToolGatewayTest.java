package com.autocve.agent.mcp.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autocve.agent.mcp.dto.McpCallContext;
import com.autocve.agent.mcp.dto.McpErrorCode;
import com.autocve.agent.mcp.dto.McpToolRequest;
import com.autocve.agent.mcp.dto.SecurityToolName;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class SpringAiSecurityToolGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void discoversMcpToolsThroughTheSpringAiClient() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(McpSchema.ListToolsResult.builder(List.of(
                        tool("profile_repository"),
                        tool("search_code"),
                        tool("start_security_scan"),
                        tool("get_scan_job"),
                        tool("get_scan_findings")))
                .build());
        SpringAiSecurityToolGateway gateway = gateway(client, new ArrayList<>());

        assertEquals(5, gateway.discoverTools().size());
        assertTrue(gateway.discoverTools().contains("start_security_scan"));
    }

    @Test
    void callsToolWithFlatTraceContextAndRecordsTheInvocation() {
        McpSyncClient client = mock(McpSyncClient.class);
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("ok", true);
        structured.put("trace_id", "trace-1");
        structured.put("request_id", "request-1");
        structured.put("data", Map.of("result_count", 1));
        structured.put("warnings", List.of());
        structured.put("artifacts", List.of());
        structured.put("error", null);
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .structuredContent(structured)
                .isError(false)
                .build());
        List<McpToolCallRecord> records = new ArrayList<>();
        SpringAiSecurityToolGateway gateway = gateway(client, records);
        McpCallContext context = new McpCallContext(
                "request-1", "trace-1", "audit-1", "project-1");
        McpToolRequest request = new McpToolRequest(
                SecurityToolName.SEARCH_CODE,
                context,
                objectMapper.readTree("{\"query\":\"authorization\"}"));

        var response = gateway.call(request);

        assertTrue(response.ok());
        assertEquals(1, response.data().path("result_count").asInt());
        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(captor.capture());
        assertEquals("trace-1", captor.getValue().arguments().get("trace_id"));
        assertEquals("authorization", captor.getValue().arguments().get("query"));
        assertEquals("success", records.getFirst().outcome());
    }

    @Test
    void mapsTransportFailureToRecognizableDependencyErrorAfterRetry() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new IllegalStateException("connection refused"));
        List<McpToolCallRecord> records = new ArrayList<>();
        SpringAiSecurityToolGateway gateway = gateway(client, records);
        McpCallContext context = new McpCallContext(
                "request-2", "trace-2", "audit-2", "project-2");
        McpToolRequest request = new McpToolRequest(
                SecurityToolName.PROFILE_REPOSITORY,
                context,
                objectMapper.readTree("{}"));

        var response = gateway.call(request);

        assertFalse(response.ok());
        assertEquals(McpErrorCode.DEPENDENCY_UNAVAILABLE, response.error().code());
        assertTrue(response.error().retryable());
        verify(client, times(2)).callTool(any());
        assertEquals(2, records.getFirst().attempts());
        assertEquals("DEPENDENCY_UNAVAILABLE", records.getFirst().errorCode());
    }

    @Test
    void neverRetriesAnApprovalGatedVerificationCall() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new IllegalStateException("connection reset"));
        List<McpToolCallRecord> records = new ArrayList<>();
        SpringAiSecurityToolGateway gateway = gateway(client, records);
        McpToolRequest request = new McpToolRequest(
                SecurityToolName.VERIFY_VULNERABILITY,
                new McpCallContext("verify-1", "trace-1", "audit-1", "project-1"),
                objectMapper.readTree("{}"));

        var response = gateway.call(request);

        assertFalse(response.ok());
        verify(client, times(1)).callTool(any());
        assertEquals(1, records.getFirst().attempts());
    }

    private SpringAiSecurityToolGateway gateway(
            McpSyncClient client,
            List<McpToolCallRecord> records) {
        return new SpringAiSecurityToolGateway(
                List.of(client),
                objectMapper,
                records::add,
                new McpGatewayProperties(2, Duration.ZERO));
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, Map.of("type", "object")).build();
    }
}
