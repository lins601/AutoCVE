package com.autocve.agent.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record InvestigationResult(
        @NotBlank @JsonProperty("coverage_summary") String coverageSummary,
        @NotNull List<@Valid InvestigationFinding> findings,
        @NotNull @JsonProperty("uncovered_areas") List<@NotBlank String> uncoveredAreas) {

    public InvestigationResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        uncoveredAreas = uncoveredAreas == null ? List.of() : List.copyOf(uncoveredAreas);
    }
}
