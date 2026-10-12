package com.autocve.agent.domain;

public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CONSUMED;

    public boolean isDecided() {
        return this != PENDING;
    }
}
