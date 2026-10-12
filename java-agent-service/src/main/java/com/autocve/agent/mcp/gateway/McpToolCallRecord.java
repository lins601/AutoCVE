package com.autocve.agent.mcp.gateway;

import java.time.Instant;
import java.util.Set;

public record McpToolCallRecord(
        String auditId,
        String toolName,
        String requestId,
        String traceId,
        Set<String> argumentNames,
        int attempts,
        long durationMs,
        String outcome,
        String errorCode,
        Instant recordedAt) {
}
