package com.autocve.agent.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerificationPlan(
        @JsonProperty("verification_mode")
        @Pattern(regexp = "sandbox")
        String verificationMode,
        @JsonProperty("verification_focus")
        @Pattern(regexp = "credential_pattern|injection_sink|access_control|state_transition|dependency_declaration|security_marker")
        String verificationFocus,
        @NotBlank String rationale) {}
