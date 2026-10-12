package com.autocve.agent.application;

public class AuditNotFoundException extends RuntimeException {
    public AuditNotFoundException(String auditId) {
        super("Agent audit was not found: " + auditId);
    }
}
