# AutoCVE Day 6 Evaluation Report

- Status: `success`
- Dataset: `1.0.0` (10 cases)

| Mode | Precision | Recall | False-positive rate | Tokens | Duration (ms) | Tool success |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Scanner-only | 0.7143 | 0.5556 | 0.2857 | 0 | 4405 | 0.9677 |
| Scanner+Agent | 0.8000 | 0.8889 | 0.2000 | 7620 | 11205 | 0.9592 |

## Agent contributions

- Supplemental finding: `py-sql-injection` — User input reaches raw SQL
- Supplemental finding: `java-missing-tenant-check` — Repository lookup omits tenant id
- Supplemental finding: `java-ssrf` — Attacker URL reaches HTTP client
- Additional evidence: `java-hardcoded-secret` — Credential is used by production HTTP client
- Additional evidence: `js-command-injection` — Upload name controls converter command
- Additional evidence: `env-secret` — Example value is referenced by deployment

## Per-case results

| Case | Scanner TP/FP/FN | Scanner+Agent TP/FP/FN |
| --- | --- | --- |
| py-sql-injection | 0/0/1 | 1/0/0 |
| java-hardcoded-secret | 1/0/0 | 1/0/0 |
| js-path-traversal | 1/0/0 | 1/0/0 |
| java-missing-tenant-check | 0/0/1 | 1/0/0 |
| go-vulnerable-dependency | 1/0/0 | 1/0/0 |
| py-sanitized-query-negative | 0/1/0 | 0/1/0 |
| js-command-injection | 1/1/0 | 1/1/0 |
| java-ssrf | 0/0/1 | 1/0/0 |
| go-debug-endpoint | 0/0/1 | 0/0/1 |
| env-secret | 1/0/0 | 1/0/0 |
