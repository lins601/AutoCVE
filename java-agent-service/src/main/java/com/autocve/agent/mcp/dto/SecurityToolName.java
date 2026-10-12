package com.autocve.agent.mcp.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum SecurityToolName {
    PROFILE_REPOSITORY("profile_repository"),
    SEARCH_CODE("search_code"),
    START_SECURITY_SCAN("start_security_scan"),
    GET_SCAN_JOB("get_scan_job"),
    GET_SCAN_FINDINGS("get_scan_findings"),
    VERIFY_VULNERABILITY("verify_vulnerability");

    private final String wireName;

    SecurityToolName(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static SecurityToolName fromWireName(String value) {
        return Arrays.stream(values())
                .filter(tool -> tool.wireName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown security tool: " + value));
    }
}
