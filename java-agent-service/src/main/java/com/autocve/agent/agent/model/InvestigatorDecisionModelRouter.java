package com.autocve.agent.agent.model;

import com.autocve.agent.mcp.dto.SecurityToolName;
import com.autocve.agent.workflow.AgentWorkflowProperties;
import java.util.Locale;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class InvestigatorDecisionModelRouter implements InvestigatorDecisionModel {

    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectMapper objectMapper;
    private final AgentWorkflowProperties properties;
    private final DeterministicInvestigatorDecisionModel fallback;

    public InvestigatorDecisionModelRouter(
            ObjectProvider<ChatModel> chatModels,
            ObjectMapper objectMapper,
            AgentWorkflowProperties properties) {
        this.chatModels = chatModels;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.fallback = new DeterministicInvestigatorDecisionModel(objectMapper);
    }

    @Override
    public InvestigatorDecision decide(InvestigatorModelRequest request) {
        ChatModel model = properties.isLlmEnabled() ? chatModels.getIfAvailable() : null;
        if (model == null) {
            return fallback.decide(request);
        }
        String content = ChatClient.create(model)
                .prompt()
                .system(request.systemPrompt())
                .user(request.userPrompt())
                .call()
                .content();
        return parse(content, estimateTokens(request.userPrompt(), content));
    }

    @Override
    public String runtimeName() {
        return properties.isLlmEnabled() && chatModels.getIfAvailable() != null
                ? "spring-ai-chat-model"
                : fallback.runtimeName();
    }

    private InvestigatorDecision parse(String raw, int estimatedTokens) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Investigator model returned an empty decision");
        }
        try {
            JsonNode root = objectMapper.readTree(stripMarkdownFence(raw));
            String action = root.path("action").asText("").toLowerCase(Locale.ROOT);
            if (action.equals("tool")) {
                SecurityToolName tool = SecurityToolName.fromWireName(root.path("tool").asText());
                JsonNode arguments = root.path("arguments");
                if (!arguments.isObject()) {
                    throw new IllegalArgumentException("Tool decision arguments must be an object");
                }
                return InvestigatorDecision.tool(tool, arguments, estimatedTokens);
            }
            if (action.equals("finalize")) {
                InvestigationResult result = objectMapper.treeToValue(
                        root.path("result"), InvestigationResult.class);
                return InvestigatorDecision.finalize(result, estimatedTokens);
            }
            throw new IllegalArgumentException("Unknown Investigator decision action");
        }
        catch (IllegalArgumentException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new IllegalArgumentException("Investigator model returned invalid JSON", exception);
        }
    }

    private static String stripMarkdownFence(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("```")) {
            int firstNewline = normalized.indexOf('\n');
            int lastFence = normalized.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return normalized.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return normalized;
    }

    private static int estimateTokens(String prompt, String response) {
        int characters = (prompt == null ? 0 : prompt.length())
                + (response == null ? 0 : response.length());
        return Math.max(1, (characters + 3) / 4);
    }
}
