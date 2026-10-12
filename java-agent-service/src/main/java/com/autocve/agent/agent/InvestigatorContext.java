package com.autocve.agent.agent;

import com.autocve.agent.agent.model.InvestigatorBudget;
import java.util.List;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;

public record InvestigatorContext(
        String auditId,
        String traceId,
        String projectId,
        JsonNode repositoryProfile,
        JsonNode scanJob,
        JsonNode scanFindings,
        List<String> targetVulnerabilities,
        InvestigatorBudget budget,
        BooleanSupplier cancellationRequested) {

    public InvestigatorContext {
        targetVulnerabilities = targetVulnerabilities == null
                ? List.of()
                : List.copyOf(targetVulnerabilities);
    }
}
