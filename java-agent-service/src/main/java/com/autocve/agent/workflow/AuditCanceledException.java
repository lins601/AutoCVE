package com.autocve.agent.workflow;

public class AuditCanceledException extends RuntimeException {
    public AuditCanceledException(String message) {
        super(message);
    }
}
