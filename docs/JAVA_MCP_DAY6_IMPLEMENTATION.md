# Day 6 实现与验收记录

> 状态：已完成  
> 验收日期：2026-07-19  
> 范围：对照评测、Micrometer 指标、日志脱敏、契约与恢复测试

## 1. 交付结果

Day 6 已完成以下能力：

- 10 个带 Ground Truth 的安全样例，覆盖 Java、Python、JavaScript、Go 和配置文件；
- Scanner-only 与 Scanner+Agent 两组结果的确定性回放评测；
- Precision、Recall、误报占比、估算 Token、总耗时和工具成功率计算；
- JSON、Markdown 两种评测报告及失败状态报告；
- Java Agent、Investigator、审批和 MCP 工具的 Micrometer 指标；
- Java 工作流异常脱敏及 Python MCP 安全日志边界；
- Java 指标/脱敏/重启恢复测试和 Java/Python MCP 契约强化测试。

## 2. 一键评测

在项目根目录执行：

```bash
./scripts/run-day6-evaluation.sh
```

默认读取：

```text
evaluation/day6/ground_truth.json
```

生成：

```text
evaluation/day6/reports/day6-evaluation.json
evaluation/day6/reports/day6-evaluation.md
```

如果数据集结构、样例数量、指标字段或 Agent 贡献不符合约束，命令以非零状态退出，只写入 `day6-evaluation.failed.json`，不会生成或保留新的成功报告。成功报告通过临时文件和原子替换提交，避免进程中断留下半份报告。

## 3. 数据集与匹配规则

数据集包含 10 个固定样例，其中 9 个正样例和 1 个负样例，覆盖：

- SQL/命令注入；
- 硬编码密钥；
- 路径穿越和跨租户越权；
- 漏洞依赖；
- SSRF；
- 未鉴权调试接口；
- 已安全处理但被扫描器误报的负样例。

Finding 使用 `category + file_path + line_start` 匹配，行号允许正负 3 行偏移；同一位置的 Scanner 与 Agent Finding 会合并 Evidence，不重复计数。指标定义：

- `Precision = TP / (TP + FP)`；
- `Recall = TP / (TP + FN)`；
- `false_positive_rate = FP / (TP + FP)`，这里表示返回结果中的误报占比；
- `tool_success_rate = successful tool calls / total tool calls`。

当前基线采用确定性回放，目的是让评测逻辑、失败语义和简历中的对照数字可以稳定复现；它不是特定线上模型版本的实时排行榜。后续替换为真实模型结果时保持相同 Finding 与用量字段即可复用评测器。

## 4. 本次对照结果

| 模式 | Precision | Recall | 误报占比 | Token | 耗时 | 工具成功率 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Scanner-only | 0.7143 | 0.5556 | 0.2857 | 0 | 4405 ms | 0.9677 |
| Scanner+Agent | 0.8000 | 0.8889 | 0.2000 | 7620 | 11205 ms | 0.9592 |

Agent 补充了 3 个 Scanner 漏检：

- Python 用户输入到原始 SQL 的跨函数数据流；
- Java Repository 查询缺少租户条件导致的越权；
- Java 预览客户端缺少 URL/地址限制导致的 SSRF。

Agent 还为硬编码密钥、命令注入和环境变量密钥补充了 3 组上下文证据。评测同时保留了一个两种模式都未发现的调试接口样例，避免把评测数据写成“全对”。

## 5. Actuator 与 Micrometer

Actuator 现在暴露 `health`、`info` 和 `metrics`。主要指标：

| 指标 | 标签 | 含义 |
| --- | --- | --- |
| `autocve.agent.audits` | `outcome`、失败时的 `stage` | 创建、完成、失败和取消任务数 |
| `autocve.agent.audit.duration` | 无 | 完整任务耗时 |
| `autocve.agent.investigation.tokens` | 无 | Investigator 估算 Token 分布 |
| `autocve.agent.investigation.iterations` | 无 | 非 Checkpoint 复用的迭代次数 |
| `autocve.agent.investigations` | `termination` | Agent 终止原因 |
| `autocve.agent.approval.decisions` | `decision` | approve、reject、expire 计数 |
| `autocve.mcp.tool.calls` | `tool`、`outcome` | MCP 工具成功/失败次数 |
| `autocve.mcp.tool.duration` | `tool`、`outcome` | MCP 工具延迟 |

指标只使用枚举或受限长度标签，不将 audit ID、project ID、请求内容等高基数字段放入标签。

运行服务后可查询：

```text
GET /actuator/metrics
GET /actuator/metrics/autocve.agent.audits
GET /actuator/metrics/autocve.mcp.tool.calls
```

## 6. 敏感信息边界

- Java `SensitiveDataRedactor` 会处理 API Key、Bearer Token、密码、Secret、私钥和 Markdown 完整代码块；
- 工作流持久化和事件中的异常消息先脱敏、去除换行并限制为 1000 字符；
- MCP 调用日志只记录工具名、请求/Trace ID、耗时、状态和参数名称，不记录参数值；
- Python MCP 的未知异常日志只记录事件名和异常类型，不输出异常文本、完整源码或堆栈；
- 验证 Artifact 保持人工复核所需的哈希、边界信息和短 Evidence，但日志不复制 Artifact 内容。

测试使用合成密钥验证日志内容，未使用或写入真实 API Key。

## 7. 测试与验收

本次验收结果：

- Java 21 Docker 构建：main/test source 均编译成功；
- Java 测试：发现 25 个，1 个可选外部 MCP 集成测试跳过，24 个执行并全部通过；
- Python 定向测试：评测成功、评测失败、日志脱敏和 MCP Schema 共 4 个通过；
- Python 新增文件通过 `py_compile`；
- Docker Compose `java-agent` profile 配置解析通过；
- 一键评测成功生成 JSON/Markdown，失败输入不会产生成功报告。

新增重点测试：

- `AgentMetricsTest`：审计、Token、审批和工具指标；
- `SensitiveDataRedactorTest`：API Key、Bearer Token、私钥和完整源码脱敏；
- `AuditRecoveryCoordinatorTest`：重启后恢复 CREATED/RUNNING/已决定任务，并保持待审批任务暂停；
- Java/Python MCP Schema：验证审批字段、64 位摘要和严格 Finding 字段；
- Day 6 Evaluation：对照指标、Agent 贡献及失败报告语义。

## 8. 范围说明

Day 6 的评测器已经可以消费真实运行结果，但仓库自带报告使用固定回放数据，因此只能表述为“可复现基线”，不能写成某个线上大模型的真实生产效果。前端指标展示、真实模型演示和最终 Compose 交付属于 Day 7。
