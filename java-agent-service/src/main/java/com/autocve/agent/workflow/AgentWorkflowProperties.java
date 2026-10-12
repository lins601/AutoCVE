package com.autocve.agent.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autocve.agent.workflow")
public class AgentWorkflowProperties {

    private boolean llmEnabled;
    private Duration scanPollInterval = Duration.ofMillis(500);
    private Duration scanPollTimeout = Duration.ofMinutes(3);
    private int maxRepeatedToolCalls = 2;
    private int maxStoredEvents = 500;
    private int maxFindingPages = 10;

    public boolean isLlmEnabled() { return llmEnabled; }
    public void setLlmEnabled(boolean llmEnabled) { this.llmEnabled = llmEnabled; }
    public Duration getScanPollInterval() { return scanPollInterval; }
    public void setScanPollInterval(Duration scanPollInterval) { this.scanPollInterval = scanPollInterval; }
    public Duration getScanPollTimeout() { return scanPollTimeout; }
    public void setScanPollTimeout(Duration scanPollTimeout) { this.scanPollTimeout = scanPollTimeout; }
    public int getMaxRepeatedToolCalls() { return maxRepeatedToolCalls; }
    public void setMaxRepeatedToolCalls(int maxRepeatedToolCalls) { this.maxRepeatedToolCalls = maxRepeatedToolCalls; }
    public int getMaxStoredEvents() { return maxStoredEvents; }
    public void setMaxStoredEvents(int maxStoredEvents) { this.maxStoredEvents = maxStoredEvents; }
    public int getMaxFindingPages() { return maxFindingPages; }
    public void setMaxFindingPages(int maxFindingPages) { this.maxFindingPages = maxFindingPages; }
}
