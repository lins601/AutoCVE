package com.autocve.agent.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autocve.agent.approval")
public class ApprovalProperties {

    private String hmacSecret = "autocve-day5-local-approval-secret-change-me";
    private Duration requestTtl = Duration.ofMinutes(30);
    private Duration tokenTtl = Duration.ofMinutes(5);
    private Duration verificationTimeout = Duration.ofSeconds(120);
    private int maxRequests = 10;

    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    public Duration getRequestTtl() { return requestTtl; }
    public void setRequestTtl(Duration requestTtl) { this.requestTtl = requestTtl; }
    public Duration getTokenTtl() { return tokenTtl; }
    public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }
    public Duration getVerificationTimeout() { return verificationTimeout; }
    public void setVerificationTimeout(Duration verificationTimeout) { this.verificationTimeout = verificationTimeout; }
    public int getMaxRequests() { return maxRequests; }
    public void setMaxRequests(int maxRequests) { this.maxRequests = maxRequests; }
}
