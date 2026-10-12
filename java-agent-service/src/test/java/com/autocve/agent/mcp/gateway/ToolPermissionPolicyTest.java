package com.autocve.agent.mcp.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autocve.agent.mcp.dto.SecurityToolName;
import org.junit.jupiter.api.Test;

class ToolPermissionPolicyTest {

    private final ToolPermissionPolicy policy = new ToolPermissionPolicy();

    @Test
    void investigatorCanOnlyUseReadOnlyTools() {
        assertTrue(policy.allowedTools(AgentRole.INVESTIGATOR)
                .contains(SecurityToolName.SEARCH_CODE));
        assertTrue(policy.allowedTools(AgentRole.INVESTIGATOR)
                .contains(SecurityToolName.GET_SCAN_FINDINGS));
        assertFalse(policy.allowedTools(AgentRole.INVESTIGATOR)
                .contains(SecurityToolName.START_SECURITY_SCAN));
        assertFalse(policy.allowedTools(AgentRole.INVESTIGATOR)
                .contains(SecurityToolName.VERIFY_VULNERABILITY));
        assertThrows(
                ToolPermissionDeniedException.class,
                () -> policy.requireAllowed(
                        AgentRole.INVESTIGATOR, SecurityToolName.START_SECURITY_SCAN));
    }

    @Test
    void onlyVerificationAgentCanInvokeTheApprovalGatedTool() {
        assertTrue(policy.allowedTools(AgentRole.VERIFICATION)
                .contains(SecurityToolName.VERIFY_VULNERABILITY));
        assertFalse(policy.allowedTools(AgentRole.WORKFLOW)
                .contains(SecurityToolName.VERIFY_VULNERABILITY));
        assertThrows(
                ToolPermissionDeniedException.class,
                () -> policy.requireAllowed(
                        AgentRole.WORKFLOW, SecurityToolName.VERIFY_VULNERABILITY));
    }
}
