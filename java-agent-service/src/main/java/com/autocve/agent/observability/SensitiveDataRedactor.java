package com.autocve.agent.observability;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SensitiveDataRedactor {

    private static final String REDACTED = "$1[REDACTED]";
    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(?is)(-----BEGIN [A-Z ]*PRIVATE KEY-----).*?(-----END [A-Z ]*PRIVATE KEY-----)"), "$1[REDACTED]$2"),
            new Rule(Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"), REDACTED),
            new Rule(Pattern.compile("(?i)(sk-)[A-Za-z0-9_-]{8,}"), REDACTED),
            new Rule(Pattern.compile("(?i)((?:api[_-]?key|access[_-]?token|secret|password)\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',;&]{4,}"), REDACTED),
            new Rule(Pattern.compile("(?i)([?&](?:api[_-]?key|access[_-]?token|secret|password)=)[^&\\s]+"), REDACTED),
            new Rule(Pattern.compile("(?s)(```(?:[A-Za-z0-9_+.-]+)?\\s*\\n).*?(```)"), "$1[SOURCE_OMITTED]$2"));

    public String redactForLog(String value) {
        if (value == null || value.isBlank()) {
            return "unknown error";
        }
        String sanitized = value;
        for (Rule rule : RULES) {
            sanitized = rule.pattern().matcher(sanitized).replaceAll(rule.replacement());
        }
        sanitized = sanitized.replace('\r', ' ').replace('\n', ' ');
        return sanitized.substring(0, Math.min(1000, sanitized.length()));
    }

    private record Rule(Pattern pattern, String replacement) {}
}
