package com.autocve.agent.application;

public class AuditConflictException extends RuntimeException {
    public AuditConflictException(String message) {
        super(message);
    }
}
