package com.autocve.agent.application;

public class ApprovalNotFoundException extends RuntimeException {
    public ApprovalNotFoundException(String approvalId) {
        super("Approval request was not found: " + approvalId);
    }
}
