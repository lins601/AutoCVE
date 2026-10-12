package com.autocve.agent.agent.model;

import com.autocve.agent.mcp.dto.SecurityToolName;
import tools.jackson.databind.JsonNode;

public record InvestigatorDecision(
        Kind kind,
        SecurityToolName tool,
        JsonNode arguments,
        InvestigationResult result,
        int estimatedTokens) {

    public enum Kind { TOOL, FINALIZE }

    public static InvestigatorDecision tool(
            SecurityToolName tool, JsonNode arguments, int estimatedTokens) {
        return new InvestigatorDecision(Kind.TOOL, tool, arguments, null, estimatedTokens);
    }

    public static InvestigatorDecision finalize(
            InvestigationResult result, int estimatedTokens) {
        return new InvestigatorDecision(Kind.FINALIZE, null, null, result, estimatedTokens);
    }
}
