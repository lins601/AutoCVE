package com.autocve.agent.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AuditRequestSpec(
        @JsonProperty("project_id") String projectId,
        @JsonProperty("target_paths") List<String> targetPaths,
        @JsonProperty("target_vulnerabilities") List<String> targetVulnerabilities,
        @JsonProperty("verification_level") String verificationLevel,
        @JsonProperty("model_profile") String modelProfile,
        List<String> scanners,
        AuditBudgets budgets) {

    public AuditRequestSpec {
        targetPaths = targetPaths == null || targetPaths.isEmpty()
                ? List.of(".")
                : List.copyOf(targetPaths);
        targetVulnerabilities = targetVulnerabilities == null
                ? List.of("authorization", "injection", "logic")
                : List.copyOf(targetVulnerabilities);
        verificationLevel = verificationLevel == null || verificationLevel.isBlank()
                ? "sandbox"
                : verificationLevel;
        modelProfile = modelProfile == null || modelProfile.isBlank()
                ? "default"
                : modelProfile;
        scanners = scanners == null ? List.of() : List.copyOf(scanners);
        budgets = budgets == null ? AuditBudgets.defaults() : budgets;
    }
}
