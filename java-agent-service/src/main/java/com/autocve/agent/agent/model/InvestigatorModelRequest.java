package com.autocve.agent.agent.model;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record InvestigatorModelRequest(
        int iteration,
        String systemPrompt,
        String userPrompt,
        JsonNode scannerFindings,
        List<String> targetVulnerabilities,
        List<String> observations) {

    public InvestigatorModelRequest {
        targetVulnerabilities = targetVulnerabilities == null
                ? List.of()
                : List.copyOf(targetVulnerabilities);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
