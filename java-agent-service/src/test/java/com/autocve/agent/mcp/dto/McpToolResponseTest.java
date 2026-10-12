package com.autocve.agent.mcp.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolResponseTest {

    @Test
    void createsSuccessfulResponseWithImmutableCollections() {
        McpToolResponse<Map<String, String>> response = McpToolResponse.success(
                "trace-1", "request-1", Map.of("status", "ready"), null, null);

        assertTrue(response.ok());
        assertEquals(List.of(), response.warnings());
        assertEquals(List.of(), response.artifacts());
    }

    @Test
    void rejectsFailedResponseWithoutError() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpToolResponse<>(
                        false, "trace-1", "request-1", null, List.of(), List.of(), null));
    }
}
