package com.autocve.agent.agent.model;

import com.autocve.agent.mcp.dto.SecurityToolName;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class DeterministicInvestigatorDecisionModel implements InvestigatorDecisionModel {

    private final ObjectMapper objectMapper;

    public DeterministicInvestigatorDecisionModel(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public InvestigatorDecision decide(InvestigatorModelRequest request) {
        if (request.iteration() == 1) {
            String focus = request.targetVulnerabilities().isEmpty()
                    ? "authorization injection business logic security"
                    : String.join(" ", request.targetVulnerabilities());
            ObjectNode arguments = objectMapper.createObjectNode();
            arguments.put("query", focus);
            arguments.put("top_k", 5);
            return InvestigatorDecision.tool(SecurityToolName.SEARCH_CODE, arguments, 0);
        }
        return InvestigatorDecision.finalize(toResult(request.scannerFindings()), 0);
    }

    @Override
    public String runtimeName() {
        return "deterministic-fallback";
    }

    public InvestigationResult toResult(JsonNode scannerFindings) {
        List<InvestigationFinding> findings = new ArrayList<>();
        JsonNode items = scannerFindings == null
                ? null
                : scannerFindings.isArray()
                        ? scannerFindings
                        : scannerFindings.path("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                String title = text(item, "title", "Scanner candidate");
                String category = normalizeCategory(text(item, "category", "other"));
                String severity = normalizeSeverity(text(item, "severity", "medium"));
                String path = text(item, "file_path", ".");
                int line = Math.max(1, item.path("line_start").asInt(1));
                List<String> evidence = strings(item.path("evidence"));
                if (evidence.isEmpty()) {
                    evidence = List.of("Scanner reported this candidate; inspect the referenced location.");
                }
                findings.add(new InvestigationFinding(
                        title,
                        category,
                        severity,
                        0.65,
                        path,
                        line,
                        line,
                        evidence,
                        List.of("Scanner candidate requires code-path review"),
                        severity.equals("critical") || severity.equals("high")));
            }
        }
        return new InvestigationResult(
                "Deterministic fallback preserved scanner evidence and performed a bounded read-only search; no LLM inference was claimed.",
                findings,
                List.of("Cross-file semantic reasoning was not executed because the Investigator LLM is disabled."));
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String text = value.asText("").trim();
            if (!text.isEmpty()) {
                result.add(text.substring(0, Math.min(2000, text.length())));
            }
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String normalizeCategory(String value) {
        return switch (value.toLowerCase()) {
            case "authorization", "injection", "logic", "secret", "dependency" ->
                    value.toLowerCase();
            default -> "other";
        };
    }

    private static String normalizeSeverity(String value) {
        return switch (value.toLowerCase()) {
            case "critical", "high", "medium", "low" -> value.toLowerCase();
            default -> "medium";
        };
    }
}
