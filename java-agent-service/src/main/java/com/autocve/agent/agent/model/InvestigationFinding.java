package com.autocve.agent.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record InvestigationFinding(
        @NotBlank String title,
        @NotBlank
        @Pattern(regexp = "authorization|injection|logic|secret|dependency|other")
        String category,
        @NotBlank
        @Pattern(regexp = "critical|high|medium|low")
        String severity,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        @NotBlank @JsonProperty("file_path") String filePath,
        @Min(1) @JsonProperty("line_start") int lineStart,
        @Min(1) @JsonProperty("line_end") int lineEnd,
        @NotEmpty List<@NotBlank String> evidence,
        @NotEmpty @JsonProperty("attack_path") List<@NotBlank String> attackPath,
        @JsonProperty("needs_verification") boolean needsVerification) {

    public InvestigationFinding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        attackPath = attackPath == null ? List.of() : List.copyOf(attackPath);
    }
}
