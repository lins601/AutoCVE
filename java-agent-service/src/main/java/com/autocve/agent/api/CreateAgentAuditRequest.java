package com.autocve.agent.api;

import com.autocve.agent.application.AuditBudgets;
import com.autocve.agent.application.AuditRequestSpec;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateAgentAuditRequest(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$")
        @JsonProperty("project_id")
        String projectId,
        @Size(max = 64) @JsonProperty("target_paths") List<@NotBlank String> targetPaths,
        @Size(max = 16)
        @JsonProperty("target_vulnerabilities")
        List<@Pattern(regexp = "authorization|injection|logic|secret|dependency|other") String>
                targetVulnerabilities,
        @Pattern(regexp = "none|sandbox")
        @JsonProperty("verification_level")
        String verificationLevel,
        @Size(max = 128) @JsonProperty("model_profile") String modelProfile,
        @Size(max = 4)
        List<@Pattern(regexp = "semgrep|gitleaks|trufflehog|osv") String> scanners,
        @Valid AgentAuditBudgetsRequest budgets) {

    AuditRequestSpec toSpec() {
        return new AuditRequestSpec(
                projectId,
                targetPaths,
                targetVulnerabilities,
                verificationLevel,
                modelProfile,
                scanners,
                budgets == null ? AuditBudgets.defaults() : budgets.toBudgets());
    }
}
