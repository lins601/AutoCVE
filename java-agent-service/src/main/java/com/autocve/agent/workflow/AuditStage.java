package com.autocve.agent.workflow;

import java.util.EnumSet;
import java.util.Set;

/**
 * Stable Day 1 workflow states. New states require a baseline change instead of
 * being introduced ad hoc by an Agent implementation.
 */
public enum AuditStage {
    CREATED,
    PREPARING,
    SCANNING,
    INVESTIGATING,
    TRIAGING,
    WAITING_APPROVAL,
    VERIFYING,
    REPORTING,
    COMPLETED,
    FAILED,
    CANCELED;

    private static final Set<AuditStage> TERMINAL_STAGES =
            EnumSet.of(COMPLETED, FAILED, CANCELED);

    public boolean isTerminal() {
        return TERMINAL_STAGES.contains(this);
    }
}
