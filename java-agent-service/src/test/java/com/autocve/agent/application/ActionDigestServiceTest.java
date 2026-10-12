package com.autocve.agent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ActionDigestServiceTest {

    @Test
    void matchesTheSharedPythonLengthPrefixedDigestVector() {
        var finding = new ObjectMapper().readTree("""
                {
                  "finding_id": "finding-1",
                  "title": "Possible SQL injection",
                  "category": "injection",
                  "file_path": "app.py",
                  "line_start": 2,
                  "evidence": ["query concatenates user input"]
                }
                """);

        String digest = new ActionDigestService().digest("audit-1", "project-1", finding);

        assertEquals(
                "af73f3df284531eac31640f0324d966009c5e3c9a5f3c48fb1ae9937b3fa0177",
                digest);
    }
}
