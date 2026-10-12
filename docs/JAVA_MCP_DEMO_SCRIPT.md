# Java + MCP Agent 5～8 分钟演示脚本

## 演示前准备

1. 在 AutoCVE 中导入一个有授权的本地测试项目，记下项目 ID。
2. 运行 `./scripts/start-java-mcp-demo.sh`，确认命令等待所有健康检查通过。
3. 打开 `http://localhost:13000/java-mcp-audit`；另开 Java health 和原 `/audit-tasks` 页面。
4. 为稳定演示默认不开真实模型；需要展示 Spring AI 时，在启动前显式设置模型环境变量。

## 时间线

### 0:00～0:50：问题与架构

说明不是全量 Java 重写：Java 负责工作流、Agent 决策、审批和可观测性，Python MCP 复用扫描/RAG/沙箱。指出 `/api/v1` 与 `/api/v2` 并存，风险可回退。

### 0:50～1:40：创建任务

选择项目、目标路径和 Gitleaks，保留 `sandbox` 验证，点击“启动 Java MCP 审计”。展示 audit ID、状态和 SSE 连接状态。

### 1:40～3:10：MCP 与 Agent

在“工作流阶段”和“MCP 工具调用”中指出：

- `profile_repository` 与扫描启动/轮询是确定性工作流；
- `search_code` 是 Investigator 根据中间证据发起的自主只读检索；
- Agent 看不到 `verify_vulnerability`，迭代、Token、时间和重复调用均有限额。

### 3:10～4:30：HITL 审批

任务进入 `WAITING_APPROVAL` 后展示 Finding、文件位置和 action digest。批准第一个验证，若有多个则拒绝其余。解释 digest 绑定、审批令牌短时有效且单次消费，未批准无法进入验证工具。

### 4:30～5:40：沙箱验证与报告

展示 `verify_vulnerability` MCP 事件、`VERIFYING → REPORTING → COMPLETED`，随后查看 verdict、Evidence、Artifact 摘要和结构化 JSON 报告。说明沙箱网络默认关闭、项目只读挂载、输出受限。

### 5:40～6:40：评测与可观测性

打开 `evaluation/day6/reports/day6-evaluation.md`：可复现基线中 Scanner-only Recall 为 0.5556，Scanner+Agent 为 0.8889；明确这是固定回放基线，不冒充线上模型成绩。展示 Actuator `autocve.mcp.tool.calls` 等指标。

### 6:40～7:30：兼容与回退

打开原 `/audit-tasks` 页面说明 Python 链路未移除。引用 `JAVA_MCP_ROLLBACK.md`，实验 profile 可单独停止，Java 表位于独立 Schema。

## 自动验收命令

```bash
./scripts/day7_full_smoke_test.py --project-id "替换为项目ID"
```

成功输出必须包含 `COMPLETED`、`search_code`、`sandbox_verification: true`、审批数量和验证 Artifact 数量。验证工具的执行证据以最终报告中的审批绑定结果与 Artifact 为准。
