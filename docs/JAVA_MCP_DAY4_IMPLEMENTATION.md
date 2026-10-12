# Java + MCP 改造 Day 4 实施记录

> 完成日期：2026-07-19  
> 对应基线：`JAVA_MCP_AGENT_REFACTOR_PLAN.md` Baseline v1  
> 状态：Day 4 已完成；未提前实现 Day 5 的 HITL、Verification Agent 或恢复流程。

## 交付结果

Java 控制面现已能够执行确定性阶段流：

```text
POST /api/v2/agent-audits
          │
          ▼
     PREPARING ── profile_repository
          │
          ▼
      SCANNING ── start_security_scan → get_scan_job → get_scan_findings
          │
          ▼
   INVESTIGATING ── bounded Investigator Agent + read-only MCP allowlist
          │
          ▼
      TRIAGING ── deterministic merge/deduplication → persisted result
```

工作流阶段由 Java 固定编排，模型不能决定跳转、创建扫描任务或进入验证阶段。每次模型或
MCP 调用前后都会重新读取运行状态，取消后不再发起后续调用。

## Investigator Agent

- 使用 Spring AI `ChatClient` 接入 OpenAI-compatible Chat Model；真实 LLM 为显式启用，
  默认采用带标签的确定性 fallback，避免没有模型时伪称产生了语义发现；
- System Prompt 明确把仓库、扫描器结果和 MCP 返回视为不可信数据，仓库中的提示词不能
  改写系统规则；
- 模型只能返回 `tool` 或 `finalize` 两类结构化决策；最终 `InvestigationResult` 使用 Jakarta
  Validation 校验后才能进入 TRIAGING 和数据库；
- Investigator 的硬白名单只有 `profile_repository`、`search_code`、`get_scan_job`、
  `get_scan_findings`；`start_security_scan` 仅允许确定性 Workflow 调用；
- 支持最大迭代数、输入 Token 估算、总时限和重复工具调用阈值；模型执行放入虚拟线程，
  控制线程按固定周期检查取消和 Deadline，超限会中断调用并给出可识别的终止原因；
- SSE 与数据库只保存工具名、参数名、耗时、状态和错误码等摘要，不保存源码、Prompt、密钥
  或完整工具响应。

主要实现：

- `java-agent-service/src/main/java/com/autocve/agent/workflow/AuditWorkflow.java`
- `java-agent-service/src/main/java/com/autocve/agent/agent/InvestigatorAgent.java`
- `java-agent-service/src/main/java/com/autocve/agent/agent/model/InvestigatorDecisionModelRouter.java`
- `java-agent-service/src/main/java/com/autocve/agent/mcp/gateway/ToolPermissionPolicy.java`

## 持久化与 API

Flyway V2 在隔离的 PostgreSQL `agent_java` Schema 中新增：

- `ai_audit_runs`：幂等键、请求快照、状态、当前阶段、取消标志、结果和乐观锁版本；
- `ai_audit_steps`：阶段、尝试次数、开始/完成时间、Checkpoint 摘要和错误；
- `ai_tool_call_records`：关联审计的 MCP 调用摘要、Trace、重试、耗时和结果状态。

对外接口：

- `POST /api/v2/agent-audits`：异步创建任务，支持 `Idempotency-Key`；
- `GET /api/v2/agent-audits/{id}`：查询状态、阶段、步骤和最终结果；
- `POST /api/v2/agent-audits/{id}/cancel`：原子标记取消；
- `GET /api/v2/agent-audits/{id}/events`：有界历史回放和实时 SSE，支持
  `Last-Event-ID`。

## 测试覆盖

Java 21 容器内 Maven 全量结果：

```text
Tests run: 16, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

跳过项是必须显式提供真实 MCP 地址与项目 ID 的可选集成测试。其余测试覆盖：

- `PREPARING → SCANNING → INVESTIGATING → TRIAGING` 全流程与落库；
- `Idempotency-Key` 重复创建不产生第二个审计；
- Investigator 结构化终止、越权工具拒绝、重复调用终止；
- 取消后不再发起下一次 MCP 调用；
- SSE 有界回放和事件序号；
- Java/Python MCP JSON Schema 契约与既有 Gateway 重试/错误映射。

## 2026-07-19 Docker 实际验收

使用 Java 21、PostgreSQL 15、Python FastMCP 和真实 Gitleaks 链路完成烟雾测试：

```json
{
  "status": "TRIAGED",
  "current_stage": "TRIAGING",
  "finding_count": 2,
  "agent_iterations": 2,
  "model_runtime": "deterministic-fallback",
  "sse_event_count": 30,
  "idempotency_verified": true,
  "cancellation_verified": true
}
```

数据库验收：Flyway 到达 V2；完整任务保存 4 个阶段和 8 条工具调用摘要；取消任务最终为
`CANCELED`，仅保留取消前已经开始的 PREPARING 调用，没有进入后续阶段。容器状态方面，
`java-agent-service`、原 Python backend、FastMCP Server、PostgreSQL 和 Redis 均为 healthy，
原前端返回 HTTP 200。

复现命令：

```bash
./scripts/docker-compose-local.sh --profile java-agent up -d java-agent-service

python3 scripts/day4_agent_smoke_test.py \
  --project-id <existing-project-id> \
  --scanner gitleaks \
  --timeout 120
```

如需使用真实 LLM，运行前显式提供 `AGENT_LLM_ENABLED=true`、
`AGENT_LLM_PROVIDER=openai`、`LLM_API_KEY`、`LLM_BASE_URL` 和 `LLM_MODEL_ID`。任何密钥都不写入
Compose、源码、日志或本实施记录。

## Day 5 边界

当前 TRIAGING 是 Day 4 的终点。尚未加入审批表、`WAITING_APPROVAL`、Verification Agent、
`verify_vulnerability`、Checkpoint 恢复或沙箱验证；这些只在 Day 5 实现。
