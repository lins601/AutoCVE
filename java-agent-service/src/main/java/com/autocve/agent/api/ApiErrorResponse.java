package com.autocve.agent.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record ApiErrorResponse(
        String code,
        String message,
        int status,
        @JsonProperty("created_at") Instant createdAt) {
}
