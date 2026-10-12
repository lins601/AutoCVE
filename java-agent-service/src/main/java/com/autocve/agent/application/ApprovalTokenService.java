package com.autocve.agent.application;

import com.autocve.agent.infrastructure.persistence.AiApprovalRequestEntity;
import com.autocve.agent.infrastructure.persistence.AiAuditRunEntity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApprovalTokenService {

    private final byte[] secret;
    private final ApprovalProperties properties;
    private final ObjectMapper objectMapper;

    public ApprovalTokenService(ApprovalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.secret = properties.getHmacSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalArgumentException("Approval HMAC secret must contain at least 32 bytes");
        }
    }

    public String issue(AiAuditRunEntity run, AiApprovalRequestEntity approval) {
        try {
            Instant expiry = Instant.now().plus(properties.getTokenTtl());
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("version", "v1");
            claims.put("approval_id", approval.getId());
            claims.put("audit_id", run.getId());
            claims.put("project_id", run.getProjectId());
            claims.put("finding_id", approval.getFindingId());
            claims.put("action_digest", approval.getActionDigest());
            claims.put("expires_at", expiry.getEpochSecond());
            byte[] payload = objectMapper.writeValueAsBytes(claims);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(payload) + "." + encoder.encodeToString(mac.doFinal(payload));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to sign approval token", exception);
        }
    }
}
