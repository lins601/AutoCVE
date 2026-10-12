package com.autocve.agent.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ApprovalDecisionRequest(
        @NotBlank
        @Pattern(regexp = "^[a-f0-9]{64}$")
        @JsonProperty("action_digest")
        String actionDigest,
        @NotBlank @Size(max = 128) @JsonProperty("decided_by") String decidedBy) {}
