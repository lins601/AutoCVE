# AutoCVE Java Agent Service

Day 4 adds the deterministic audit workflow and the bounded Investigator Agent to the Java
control plane. The existing Spring AI synchronous MCP Client remains the only route to the
Python security tools.

## Local verification

```bash
./mvnw test
./mvnw spring-boot:run
curl http://localhost:8081/actuator/health
```

The default profile uses an in-memory H2 database so the health check can run without changing the existing AutoCVE stack. Docker Compose supplies PostgreSQL settings and writes Flyway metadata to the isolated `agent_java` schema.

## Runtime boundary

- Java owns workflow control, approval, persistence, events, Agent calls and the MCP client.
- Python keeps project access, RAG, scanners, ARQ and sandbox execution.
- `../contracts/mcp/security-tools.schema.json` remains the only cross-language contract source.
- Python scan submission is asynchronous; Java polls `get_scan_job` and then reads
  cursor-paginated `get_scan_findings`.
- Flyway owns the Day 4 workflow tables in the isolated `agent_java` schema.

## Day 4 API

- `POST /api/v2/agent-audits` creates an idempotent asynchronous audit.
- `GET /api/v2/agent-audits/{id}` returns stage, status, statistics and triage result.
- `POST /api/v2/agent-audits/{id}/cancel` prevents later model and MCP calls.
- `GET /api/v2/agent-audits/{id}/events` streams replayable summary-only SSE events.

The deterministic workflow owns `PREPARING → SCANNING → INVESTIGATING → TRIAGING`.
Only the Investigator phase can use model reasoning. Its hard tool allowlist contains
`profile_repository`, `search_code`, `get_scan_job` and `get_scan_findings`; scan
submission stays a workflow-only operation.

LLM use is opt-in. Set `AGENT_LLM_ENABLED=true`, `AGENT_LLM_PROVIDER=openai` and the
OpenAI-compatible `LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL_ID` variables. With LLM
disabled, the service runs a labeled deterministic Investigator fallback that preserves
scanner evidence without claiming semantic model findings.
