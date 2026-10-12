# Java + MCP 改造 Day 3 实施记录

> 实施日期：2026-07-18  
> 对应基线：`JAVA_MCP_AGENT_REFACTOR_PLAN.md` Baseline v1  
> 范围：扫描任务 MCP 化与 Java Spring AI MCP Client，不提前实现 Day 4 Agent 工作流。

## 已实现架构

```text
Java SecurityToolGateway
        │  Spring AI MCP Client / JDK Streamable HTTP
        ▼
Python FastMCP /mcp
        │  request_id 幂等创建任务
        ▼
Redis Job State + ARQ Queue
        │
        ▼
Dedicated MCP Scan Worker
        │  read-only project mount + Docker Sandbox
        ▼
Semgrep / Gitleaks / TruffleHog / OSV
```

MCP HTTP 请求只完成校验、幂等创建和 ARQ 入队，不等待扫描器结束。调用方通过
`get_scan_job` 轮询，再通过 `get_scan_findings` 按游标读取标准化结果。

## Python 工具平面

- 新增 `start_security_scan`、`get_scan_job`、`get_scan_findings`，MCP Server 共暴露
  5 个 Day 2/3 工具；
- `project_id + request_id` 作为幂等键，相同参数返回原任务，不重复入队；相同
  `request_id` 携带不同扫描参数返回 `CONFLICT`；
- Redis 保存任务、扫描器运行摘要和标准化 Finding，默认 TTL 为 7 天；
- Finding 支持 `critical/high/medium/low` 过滤、最大 100 条分页和绑定 `job_id` 的
  不透明游标；
- 独立 `mcp-scan-worker` 复用现有 ARQ、SandboxManager 和四类扫描器，项目目录始终
  只读挂载；
- 扫描请求重复执行、路径穿越、跨项目或跨审计读取、非法游标和依赖故障均返回统一错误结构。

关键实现：

- `backend/app/services/mcp_scan_runtime/service.py`
- `backend/app/services/mcp_scan_runtime/store.py`
- `backend/app/services/mcp_scan_runtime/runner.py`
- `backend/app/worker/mcp_scan_worker.py`
- `backend/scripts/mcp_day3_smoke_test.py`

## Java 控制平面

- 引入 Spring AI 2.0 标准 `spring-ai-starter-mcp-client`；标准 Starter 使用 JDK
  `HttpClient` Streamable HTTP Transport；
- JDK `HttpClient` 固定为 HTTP/1.1，避免明文 HTTP/2 Upgrade 与 Python ASGI Server
  的请求体处理冲突；
- `SecurityToolGateway` 提供工具发现以及 profile、search、start、poll、findings 五类调用；
- Spring AI 请求超时为 20 秒，Resilience4j Retry 默认最多 2 次、间隔 250 ms；
- `request_id`、`trace_id`、`audit_id`、`project_id` 以平铺参数传入 Python，同时写入
  MDC；
- 每次调用记录工具名、参数名、Trace、重试次数、耗时、结果和错误码，不记录代码内容
  或密钥值；
- 连接失败统一转换为可识别的 `DEPENDENCY_UNAVAILABLE`，Java 服务本身无需把依赖
  故障伪装为业务错误。

关键实现：

- `java-agent-service/src/main/java/com/autocve/agent/mcp/gateway/SecurityToolGateway.java`
- `java-agent-service/src/main/java/com/autocve/agent/mcp/gateway/SpringAiSecurityToolGateway.java`
- `java-agent-service/src/main/java/com/autocve/agent/mcp/gateway/McpHttpTransportConfiguration.java`
- `java-agent-service/src/test/java/com/autocve/agent/mcp/gateway/JavaPythonMcpIntegrationTest.java`

## 2026-07-18 Docker 验收结果

- 成功构建 `autocve-ui-rebrand-backend:latest` 与
  `autocve-java-agent-service:day3`；
- Python MCP 测试：19 passed；Day 3 代码 Ruff：All checks passed；
- 原项目目录 API 回归：19 passed；
- Java 测试：9 tests，0 failures，0 errors，1 个真实集成测试在普通测试中按环境跳过；
- Java + Python 真实集成测试：1 passed，完成 5 个工具发现、profile、search、异步
  Gitleaks 扫描、轮询与 findings 读取；
- Docker smoke：`request_id` 幂等验证成功，作业状态 `completed`，扫描器产出并索引
  2 条 finding；
- `java-agent-service`、`autocve-mcp-server`、PostgreSQL、Redis 与原 backend 均为
  healthy，原前端返回 HTTP 200；
- 连接失败到 `DEPENDENCY_UNAVAILABLE` 的转换与最多 2 次重试由
  `SpringAiSecurityToolGatewayTest` 覆盖。

## 验收命令

```bash
./scripts/docker-compose-local.sh --profile java-agent build \
  autocve-mcp-server java-agent-service sandbox
./scripts/docker-compose-local.sh --profile java-agent up -d \
  autocve-mcp-server mcp-scan-worker java-agent-service

./scripts/docker-compose-local.sh exec autocve-mcp-server \
  /app/.venv/bin/python /app/scripts/mcp_day3_smoke_test.py \
  --project-id <existing-project-id> --scanner gitleaks
```

Java + Python 集成测试通过环境变量连接真实 MCP Server：

```bash
MCP_INTEGRATION_URL=http://autocve-mcp-server:8001 \
MCP_INTEGRATION_PROJECT_ID=<existing-project-id> \
mvn -Dtest=JavaPythonMcpIntegrationTest test
```

## Day 4 边界

本日没有实现 Agent 循环、工作流状态机、审批表、Agent Tool 白名单、SSE 或模型调用。
这些仍从 Day 4 的 `PREPARING → SCANNING → INVESTIGATING → TRIAGING` 开始。
