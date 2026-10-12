package com.autocve.agent.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

    private final SensitiveDataRedactor redactor = new SensitiveDataRedactor();

    @Test
    void removesApiKeysBearerTokensPrivateKeysAndCompleteSource() {
        String sensitive = "api_key=sk-secret-123456789 bearer abcdefghijklmnop\n"
                + "-----BEGIN PRIVATE KEY-----private-material-----END PRIVATE KEY-----\n"
                + "```java\nclass Secret { String key = \"value\"; }\n```";

        String result = redactor.redactForLog(sensitive);

        assertFalse(result.contains("sk-secret-123456789"));
        assertFalse(result.contains("abcdefghijklmnop"));
        assertFalse(result.contains("private-material"));
        assertFalse(result.contains("class Secret"));
        assertTrue(result.contains("[REDACTED]") || result.contains("[SOURCE_OMITTED]"));
    }
}
