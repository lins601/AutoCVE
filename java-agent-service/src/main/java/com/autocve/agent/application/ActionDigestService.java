package com.autocve.agent.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ActionDigestService {

    public String digest(String auditId, String projectId, JsonNode finding) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "version", "v1");
            add(digest, "action_type", "verify_vulnerability");
            add(digest, "verification_mode", "sandbox");
            add(digest, "audit_id", auditId);
            add(digest, "project_id", projectId);
            add(digest, "finding_id", finding.path("finding_id").asText(""));
            add(digest, "title", finding.path("title").asText(""));
            add(digest, "category", finding.path("category").asText(""));
            add(digest, "file_path", finding.path("file_path").asText(""));
            add(digest, "line_start", finding.path("line_start").asText(""));
            JsonNode evidence = finding.path("evidence");
            add(digest, "evidence_count", evidence.isArray() ? Integer.toString(evidence.size()) : "0");
            if (evidence.isArray()) {
                for (int index = 0; index < evidence.size(); index++) {
                    add(digest, "evidence." + index, evidence.get(index).asText(""));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to create approval action digest", exception);
        }
    }

    private static void add(MessageDigest digest, String name, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((name + ":" + bytes.length + ":").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
        digest.update((byte) '\n');
    }
}
