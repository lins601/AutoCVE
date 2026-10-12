package com.autocve.agent.mcp.gateway;

import com.autocve.agent.mcp.dto.McpCallContext;
import com.autocve.agent.mcp.dto.McpToolRequest;
import com.autocve.agent.mcp.dto.McpToolResponse;
import com.autocve.agent.mcp.dto.SecurityToolName;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public interface SecurityToolGateway {

    Set<String> discoverTools();

    McpToolResponse<JsonNode> call(McpToolRequest request);

    default McpToolResponse<JsonNode> profileRepository(McpCallContext context, JsonNode arguments) {
        return call(new McpToolRequest(SecurityToolName.PROFILE_REPOSITORY, context, arguments));
    }

    default McpToolResponse<JsonNode> searchCode(McpCallContext context, JsonNode arguments) {
        return call(new McpToolRequest(SecurityToolName.SEARCH_CODE, context, arguments));
    }

    default McpToolResponse<JsonNode> startSecurityScan(McpCallContext context, JsonNode arguments) {
        return call(new McpToolRequest(SecurityToolName.START_SECURITY_SCAN, context, arguments));
    }

    default McpToolResponse<JsonNode> getScanJob(McpCallContext context, JsonNode arguments) {
        return call(new McpToolRequest(SecurityToolName.GET_SCAN_JOB, context, arguments));
    }

    default McpToolResponse<JsonNode> getScanFindings(McpCallContext context, JsonNode arguments) {
        return call(new McpToolRequest(SecurityToolName.GET_SCAN_FINDINGS, context, arguments));
    }

    default McpToolResponse<JsonNode> verifyVulnerability(
            McpCallContext context, JsonNode arguments) {
        return call(new McpToolRequest(SecurityToolName.VERIFY_VULNERABILITY, context, arguments));
    }
}
