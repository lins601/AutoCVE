package com.autocve.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuditStageTest {

    @Test
    void onlyCompletionFailureAndCancellationAreTerminal() {
        assertTrue(AuditStage.COMPLETED.isTerminal());
        assertTrue(AuditStage.FAILED.isTerminal());
        assertTrue(AuditStage.CANCELED.isTerminal());
        assertFalse(AuditStage.WAITING_APPROVAL.isTerminal());
        assertFalse(AuditStage.INVESTIGATING.isTerminal());
    }
}
