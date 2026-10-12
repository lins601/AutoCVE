package com.autocve.agent.mcp.gateway;

import com.autocve.agent.mcp.dto.SecurityToolName;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ToolPermissionPolicy {

    private final Map<AgentRole, Set<SecurityToolName>> permissions =
            new EnumMap<>(AgentRole.class);

    public ToolPermissionPolicy() {
        permissions.put(
                AgentRole.INVESTIGATOR,
                EnumSet.of(
                        SecurityToolName.PROFILE_REPOSITORY,
                        SecurityToolName.SEARCH_CODE,
                        SecurityToolName.GET_SCAN_JOB,
                        SecurityToolName.GET_SCAN_FINDINGS));
        permissions.put(
                AgentRole.WORKFLOW,
                EnumSet.of(
                        SecurityToolName.PROFILE_REPOSITORY,
                        SecurityToolName.START_SECURITY_SCAN,
                        SecurityToolName.GET_SCAN_JOB,
                        SecurityToolName.GET_SCAN_FINDINGS));
        permissions.put(
                AgentRole.VERIFICATION,
                EnumSet.of(
                        SecurityToolName.SEARCH_CODE,
                        SecurityToolName.VERIFY_VULNERABILITY));
    }

    public Set<SecurityToolName> allowedTools(AgentRole role) {
        return Set.copyOf(permissions.getOrDefault(role, Set.of()));
    }

    public void requireAllowed(AgentRole role, SecurityToolName tool) {
        if (!allowedTools(role).contains(tool)) {
            throw new ToolPermissionDeniedException(
                    "Tool " + tool.wireName() + " is not allowed for " + role.name());
        }
    }
}
