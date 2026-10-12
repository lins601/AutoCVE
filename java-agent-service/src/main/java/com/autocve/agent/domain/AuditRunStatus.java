package com.autocve.agent.domain;

public enum AuditRunStatus {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    TRIAGED,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == COMPLETED || this == TRIAGED || this == FAILED || this == CANCELED;
    }
}
