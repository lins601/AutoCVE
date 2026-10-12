# AutoCVE 简历前两条：代码核验、通俗解释与面试回答

> 核验日期：2026-07-23  
> 核验范围：当前工作区中的 Python Multi-Agent 主链路、Finding Runtime、上下文压缩、持久化，以及独立 Java/Spring AI + MCP 实验控制面。  
> 写作原则：只写代码能够证明的事实；“设计目标”“当前实现”“仍有缺口”分开说明。

---

## 一、先给结论：纠错后的简历表述

### 第一条：Python 多角色 Agent 审计主链路

推荐写法：

> 构建 Python 多角色 Agent 源码审计主链路，由 Orchestrator（编排器）以确定性工作流依次编排 Recon（仓库画像）、Finding（直接源码审计）、确定性 ScanPipeline（扫描流水线）、Triage Runtime（扫描分诊）与 Verification（漏洞验证），汇合直接审计和扫描分诊两路 Finding；记录阶段事件、Runtime 会话、工具调用、Checkpoint（检查点）与结构化 Finding，支持任务追踪、故障定位和历史复盘。

这句话刻意使用了：

- “依次编排”，因为当前执行是串行的；
- “两路 Finding”，因为它们是两种结果来源，不是并行线程；
- “多角色 Agent + 确定性流水线”，因为 ScanPipeline 不是 Agent；
- “历史复盘”，因为当前能查看历史轨迹，但不能保证确定性重放；
- “故障定位”，而没有把整个 Python Orchestrator 夸大成服务重启后从任意阶段自动恢复。

### 第二条：Finding Agent 的 ReAct Runtime

推荐写法：

> 为 Finding Agent 落地 ReAct Runtime（推理与行动交替运行时），通过分角色 Tool Registry（工具注册表）、会话与 Skill 权限校验、可配置轮次上限、字符级上下文及工具结果预算、Nudge（运行时纠偏提醒）和 FinalizeFinding（结构化终止工具）约束重复调查、上下文膨胀与未提交结果；实现可配置的边界过滤、确定性裁剪、摘要压缩及超长请求恢复。

这条不能继续写成“精确 Token 预算”，原因是当前 Python 代码主要通过 `len(content)` 统计字符数，并没有调用 tokenizer（分词器）计算真实 Token。

### Java/Spring AI + MCP 建议单独写

推荐单独放在后端条目：

> 新增独立 Java 21/Spring Boot/Spring AI + MCP 实验控制面，以固定工作流编排仓库画像、异步扫描、Investigator 调查、人工审批、沙箱验证和报告生成，并通过 Python FastMCP 暴露 6 个白名单安全工具；该 `/api/v2` 链路与原 Python 审计主链路并存，不替换原有流程。

不建议把 Java 控制面揉进第一条，因为这容易让面试官误以为：

> Java 控制面正在调用并接管 Python 的 Recon、Finding、Triage 和 Verification。

实际代码中，两者是独立入口、独立工作流。

---

## 二、必须纠正的内容

| 原说法或容易产生的理解 | 代码核验结果 | 应改成 |
|---|---|---|
| Recon、Finding、Scan、Triage、Verification 是五个同构 Agent | 不准确。Scan 当前走 `ScanPipeline`；Triage 走独立 Runtime；Merge 和 Finalize 是普通 Python 方法 | 多角色 Agent 与确定性扫描阶段混合编排 |
| Finding 和扫描分支并行运行 | 不准确。元数据明确写着 `finding_first_sequential` | Finding 优先串行执行，最后汇合两路结果 |
| 两条分析分支 | 容易误导 | 两路 Finding 结果来源 |
| 所有 Agent 必须使用同一个模型 | 不准确。默认可以继承同一配置，也支持按角色覆盖供应商、模型和参数 | 各角色拥有独立 LLMService 配置，可同模也可异模 |
| 可复盘就是可以完全重放 | 不准确 | 可以查看历史消息、事件和工具轨迹，但不保证再次执行结果完全一致 |
| Java 控制面接管 Python Multi-Agent | 不准确 | Java 是独立 `/api/v2` 实验链路，与 Python 主链路并存 |
| 角色级硬编码工具白名单 | 不够准确 | 按 Agent 类型构建 Tool Registry，再叠加会话规则、Plan Mode、Skill `allowed_tools` 和延迟工具激活 |
| 精确 Token 预算 | 不准确 | 当前主要是字符级近似预算与轮次上限 |
| Finding Runtime 有默认硬迭代上限 | 不一定。`max_turns` 可配置；未配置时 Runtime 可以是 `None` | 实现可配置轮次上限 |
| 有独立总工具调用次数预算 | 当前新版 Python QueryLoop 未看到独立的总工具调用硬上限 | 不写“工具调用预算”，只写“工具结果上下文预算” |
| 自动上下文压缩默认始终启用 | 不准确。标准 Finding Runtime 创建流程没有默认注入 `query_context.pipeline` 和 `autocompact_controller` | 实现可配置自动压缩框架；配置后生效 |
| 自动压缩阈值 95,000 Token | 不准确。阈值公式虽然使用 Token 命名，但实际使用字符数与其比较 | 只能说“代码中的数值阈值”，不能说“真实 95,000 Token” |
| 13,000 安全缓冲来自严格计算 | 代码没有给出实验推导 | 13,000 是硬编码工程余量，当前缺少基准测试依据 |
| 多次压缩可靠地使用“旧摘要 + 新消息”滚动总结 | 当前边界集合可能把摘要本身也过滤掉，不能这样宣称 | 这是设计目标，但当前实现存在摘要被切掉的风险 |
| FinalizeFinding 保证模型一定成功提交 | 不准确 | 它提供终止契约、Schema 校验、有限次 Nudge 和恢复尝试；仍不提交则标记 incomplete |
| 上下文压缩会清理数据库 | 不准确 | 压缩主要作用于下一次模型输入，原始数据库消息通常仍保留 |

---

# 第一条详细解释：Python 多角色 Agent 审计主链路

## 1. 这条主链路要解决什么问题？

源码审计不是一次问答，而是一个长流程：

1. 先了解仓库结构；
2. 再主动阅读源码、跟踪数据流；
3. 使用确定性扫描器匹配已知漏洞模式；
4. 对扫描结果去除误报；
5. 合并不同来源的漏洞候选；
6. 对高价值候选继续验证；
7. 最后形成结构化、可保存的 Finding。

如果全部交给一个 Agent，它同时需要记住：

- 项目技术栈；
- 入口与高风险目录；
- 已经读过的文件；
- 尚未闭合的数据流；
- 扫描器输出；
- 验证要求；
- 最终结果格式。

随着消息增加，角色目标、上下文和工具权限会混在一起。因此项目采用的是：

> 多角色 Agent + 确定性扫描阶段 + 固定 Orchestrator。

Multi-Agent（多智能体）的重点不只是“多个模型请求”，而是把角色、提示词、工具、上下文、状态和输出契约拆开。

## 2. 每个阶段到底是什么身份？

| 名称 | 准确中文 | 当前身份 | 主要职责 |
|---|---|---|---|
| `Orchestrator` | 编排器 | `OrchestratorAgent` 类，但主阶段由代码固定 | 构建计划、传递结果、合并 Finding、选择验证候选 |
| `Recon` | 仓库侦察、仓库画像 | `ReconAgent` | 识别技术栈、入口、高风险目录和扫描建议 |
| `Finding` | 漏洞发现 | `FindingAgent`，可进入新版 Runtime | 主动搜索和阅读源码，形成直接审计 Finding |
| `ScanPipeline` | 确定性扫描流水线 | 普通流水线，不是 Agent | 执行规则扫描并产生原始候选 |
| `Triage Runtime` | 扫描分诊运行时 | 分诊角色 Runtime | 分批研判扫描结果、降低误报 |
| `Verification` | 漏洞验证 | `VerificationAgent` | 对高价值候选继续验证 |
| `Merge` | 合并去重 | 普通 Python 方法 | 归一化、字段规则去重、补全信息 |
| `Finalize` | 最终收口 | 普通 Python 方法 | 合入验证状态并生成最终 Finding 列表 |

关键代码：

- `backend/app/services/agent/agents/orchestrator.py:683-862`
- `backend/app/services/agent/agents/recon.py:77`
- `backend/app/services/agent/agents/finding.py:231`
- `backend/app/services/agent/agents/verification.py:338`
- `backend/app/services/scan_runtime/pipeline.py`

需要特别注意：

> 仓库中虽然也存在 `ScanAgent` 类，并且启动时会实例化，但当前 Orchestrator 的主路径调用的是 `_run_scan_pipeline()`，不是 `ScanAgent.run()`。

因此面试中不要说“扫描 Agent 与 Finding Agent 并行协作”。

## 3. 实际执行顺序

当前时间顺序是：

```text
_build_execution_plan()
        ↓
Recon
        ↓
Finding
        ↓
ScanPipeline
        ↓
Triage Runtime
        ↓
_merge_findings()
        ↓
_select_findings_for_verification()
        ↓
Verification
        ↓
_finalize_findings()
```

最直接的代码证据是：

```python
"execution_mode": "finding_first_sequential"
```

中文是：

> 执行模式：Finding 优先的串行执行。

代码位置：

- Recon：`orchestrator.py:720`
- Finding：`orchestrator.py:753`
- ScanPipeline：`orchestrator.py:779`
- Triage Runtime：`orchestrator.py:789`
- Merge：`orchestrator.py:827`
- Verification：`orchestrator.py:834`
- Finalize：`orchestrator.py:862`

当前没有使用 `asyncio.gather()` 或 `asyncio.create_task()` 同时启动 Finding 和 Scanner。

另外，Finding 失败后代码会提前返回，ScanPipeline 不再执行。因此它们也不是两个彼此独立、互不影响的容错分支。

## 4. “两路 Finding”在哪里？

第一路是 Finding Agent 直接审计源码得到的结果：

```python
direct_findings = finding_result.data.get("findings", [])
```

第二路是 Scanner 产生候选，再由 Triage 研判后的结果：

```python
triage_findings = triage_result.data.get("findings", [])
```

两路结果在这里合并：

```python
merged_findings = self._merge_findings([
    triage_findings,
    direct_findings,
])
```

所以正确理解是：

```text
时间顺序：

Recon → Finding → ScanPipeline → Triage → Merge → Verification


Merge 接收的数据来源：

Finding 直接源码审计结果 ──────┐
                              ├─→ 合并去重 → Verification
Scanner + Triage 结果 ────────┘
```

## 5. 两路 Finding 如何合并？

`_merge_findings()` 不是向量相似度，也没有再调用 LLM 判断重复。

它使用的去重键是：

```text
file_path + line_start + vulnerability_type
文件路径 + 起始行号 + 漏洞类型
```

代码位置：

`backend/app/services/agent/agents/orchestrator.py:520-571`

命中同一个键以后，代码会：

- 取更高的 `confidence`；
- 取更高的 `severity`；
- 优先保留更长的描述和代码片段；
- 补齐缺失的 source、sink、PoC、影响和验证说明；
- 合并 `origin`；
- 重新计算 `report_status`。

这种方法的优点是：

- 快；
- 确定；
- 不增加模型调用；
- 行为容易测试。

缺点是：

- 同一漏洞如果被两路结果定位到相邻但不同的行，可能无法去重；
- 同一根因如果表现为不同漏洞类型，也可能留下两条；
- 不具备语义相似度去重能力。

面试中不能说“使用向量语义相似度完成 Finding 去重”。

## 6. 这些 Agent 是否调用同一个大模型 API？

启动任务时，代码分别创建：

- `orchestrator_llm_service`
- `recon_llm_service`
- `analysis_llm_service`
- `scan_llm_service`
- `triage_llm_service`
- `finding_llm_service`
- `verification_llm_service`

代码位置：

`backend/app/api/v1/endpoints/agent_tasks.py:1301-1307`

默认情况下，这些实例可以继承同一套：

- LLM Provider（模型供应商）；
- API Key（接口密钥）；
- Base URL（接口地址）；
- Model（模型）；
- Temperature（采样温度）；
- Max Tokens（最大输出 Token）。

但是 `agentConfigs` 支持按角色覆盖这些参数：

`backend/app/api/v1/endpoints/agent_tasks.py:1252-1277`

所以准确回答是：

> 当前架构允许多个角色共用同一个底层模型，也允许按角色单独配置模型。Multi-Agent 与“是否同一个模型”是两个不同维度。

### 即使使用同一个模型，拆 Agent 有什么价值？

一个 Agent 实例可以理解为：

```text
底层模型
+ 角色提示词
+ 可用工具
+ 私有上下文
+ 预算
+ 状态
+ 输出契约
```

因此即使底层模型相同，拆分后仍然有以下好处：

1. 职责隔离  
   Recon 负责画地图，Finding 负责找漏洞，Triage 负责判断扫描结果，Verification 负责验证。

2. 上下文隔离  
   Recon 的大量探索过程不必完整进入 Verification。

3. 工具隔离  
   不同角色可以注册不同工具，并叠加不同权限。

4. 提示词专门化  
   每个角色只需要遵守本阶段目标和输出格式。

5. 可观测性  
   可以分别统计每个阶段的耗时、工具调用、错误和产出。

6. 可替换性  
   后续可以给 Recon 使用便宜模型，给 Finding 或 Verification 使用更强模型。

7. 可评估性  
   可以分别评价仓库画像、漏洞发现、分诊和验证质量。

对应代价是：

- 模型调用次数增加；
- 总延迟和费用可能增加；
- 阶段之间需要稳定的数据契约；
- 上游错误可能传递给下游；
- Orchestrator、持久化和恢复逻辑更复杂。

## 7. “可追踪、可复盘”是怎么实现的？

它不是只保存最终 Finding，而是保存两层运行记录。

### 7.1 外层 AgentTask 任务记录

主要包括：

- `AgentTask`：任务状态、当前阶段、统计信息；
- `AgentEvent`：阶段事件、消息、工具输入输出、序号和时间；
- `AgentFinding`：最终结构化漏洞记录。

代码位置：

- `backend/app/models/agent_task.py:48`
- `backend/app/models/agent_task.py:225`
- `backend/app/models/agent_task.py:329`
- `backend/app/api/v1/endpoints/agent_tasks.py:2010`

`AgentEvent` 有 `sequence` 和 `created_at`，事件查询支持：

```text
after_sequence + limit
```

即按序号向后分页读取。

### 7.2 Finding/Triage Runtime 会话记录

新版 Runtime 进一步保存：

- `AuditSessionMessage`：会话消息；
- `AuditSessionTurn`：模型轮次；
- `AuditModelStreamAttempt`：流式模型请求尝试；
- `AuditToolCall`：工具调用；
- `AuditCheckpoint`：检查点；
- Skill、Memory、Handoff 等运行时数据。

代码位置：

- `backend/app/models/audit_session.py:38-210`
- `backend/app/services/finding_runtime/session_store.py`

因此出错时可以回答：

- 任务执行到了哪个阶段；
- 模型在哪一轮停止；
- 调用了哪个工具；
- 工具输入、输出和耗时是什么；
- 是否发生模型流重试；
- 是否产生 Checkpoint；
- 最终 Finding 来自直接审计还是扫描分诊。

### 7.3 “复盘”不等于“确定性重放”

这里的 Replay 应准确翻译成：

- `historical review`：历史复盘、历史审查；
- `deterministic replay`：确定性重放。

当前项目支持前者，不保证后者。

因为再次运行时，以下内容可能变化：

- 模型版本；
- 模型随机性；
- 提示词；
- 工具结果；
- 仓库内容；
- 沙箱环境；
- 外部依赖。

所以简历写“过程可复盘”可以，写“百分之百重放”不可以。

## 8. 消息都写数据库，会不会撑爆？

会话消息正文确实会保存到 PostgreSQL：

```python
content = Column(Text)
message_metadata = Column(JSON)
payload = Column(JSON)
```

`append_message()` 每写一条消息会：

1. 查询该会话当前最大 `sequence`；
2. 使用 `MAX(sequence) + 1` 生成下一个序号；
3. 插入消息；
4. 单独 `commit()`；
5. `refresh()`。

代码位置：

- `backend/app/services/finding_runtime/session_store.py:127-150`
- `backend/app/services/finding_runtime/session_store.py:556-559`

PostgreSQL 不会因为一条长文本立即崩溃，真正风险来自长期累计：

- 表和索引持续增长；
- WAL 和备份增长；
- 大量逐条提交增加 I/O；
- 连接池被频繁写入占用；
- 全会话读取导致应用内存压力；
- 磁盘最终可能被占满。

### 当前已经做的控制

- 高频 `thinking_token` 不写 PostgreSQL；
- 流式 Token 会按数量或时间聚合后发送；
- 部分 Agent 事件中的工具输出只保存预览；
- 内存事件队列有最大长度，满时优先丢低价值实时事件；
- 可选 Redis Stream 有最大长度；
- AgentEvent 查询有游标分页。

### 当前仍存在的不足

- 会话消息和普通事件仍以逐条提交为主；
- 会话正文没有统一大小上限；
- `load_session_snapshot()` 会加载当前会话的全部关联数据；
- 会话消息接口缺少完整游标分页；
- 没有看到完整 TTL、冷热归档和按时间分区；
- `MAX(sequence) + 1` 在同会话并发写入时存在竞争风险；
- `runtime_state_json` 会保存 QueryLoop 工作状态，其中包含当前消息列表，存在重复存储和写放大。

因此面试中应坦诚回答：

> 当前已经完成审计追踪所需的基础持久化，并对最高频的 Token 事件做了降频；但批量写入、大正文外置、消息分页、归档和表分区仍属于生产化改造项，所以我不会把它描述成已经解决海量会话高并发存储。

### 上下文压缩不会减小数据库历史

工具原始结果会先写入消息表：

`backend/app/services/finding_runtime/query_loop.py:1477-1498`

随后工具结果预算、Microcompact 和摘要压缩主要操作“下一次发送给模型的消息副本”。

因此：

```text
数据库原始消息：通常继续保留

下一次模型输入：可以被过滤、裁剪或摘要
```

不能把“上下文压缩”解释成“数据库自动清理”。

## 9. Java/Spring AI + MCP 与 Python 主链路是什么关系？

当前仓库存在两条并存链路：

```text
Python /api/v1 Multi-Agent 审计主链路

Java /api/v2 Agent + MCP 实验控制面
```

Java 自己的工作流阶段是：

```text
CREATED
→ PREPARING
→ SCANNING
→ INVESTIGATING
→ TRIAGING
→ WAITING_APPROVAL
→ VERIFYING
→ REPORTING
→ COMPLETED
```

代码位置：

- `java-agent-service/src/main/java/com/autocve/agent/workflow/AuditStage.java:10-21`
- `java-agent-service/src/main/java/com/autocve/agent/workflow/AuditWorkflow.java:94-157`

Java 通过 MCP（Model Context Protocol，模型上下文协议）调用 Python 安全工具面，而不是直接调用 Python Orchestrator 的 Recon/Finding 等 Agent。

还要注意：

> Java 默认配置中 `AGENT_LLM_ENABLED=false` 时使用确定性 Investigator；显式启用后才由 Spring AI 模型参与决策。

因此只能说“新增独立 Java/Spring AI + MCP 实验控制面”，不能说“Java 已接管 Python Multi-Agent”。

---

# 第一条面试回答

## 1. 30 秒版本

> 这个项目不是把整个仓库一次性发给大模型，而是把源码审计拆成仓库画像、直接 Finding、确定性扫描、扫描分诊和漏洞验证几个职责。Orchestrator 当前按固定顺序执行这些阶段，最后汇合 Finding Agent 的直接审计结果和 Scanner 经 Triage 处理后的结果。整个过程会记录阶段事件、Runtime 消息、工具调用、Checkpoint 和 Finding，因此能定位任务在哪一阶段失败，也能回看 Finding 的形成过程。

## 2. 90 秒版本

> 我把源码审计设计成多角色 Agent 与确定性扫描器协作。Recon 先识别技术栈、入口和高风险模块；Finding Agent 再主动搜索和阅读源码，补充传统规则难以识别的跨文件数据流和业务逻辑问题。随后 ScanPipeline 执行确定性扫描，Triage Runtime 对扫描候选进行分批研判。两路结果不是并行执行，而是按 Finding 优先的固定顺序执行，最后通过文件路径、起始行号和漏洞类型做字段规则去重，再选取高危或需要验证的候选进入 Verification。
>
> 多角色的价值不只是扩大上下文。即使角色共用同一个底层模型，它们仍然拥有不同提示词、工具集合、上下文、预算和输出契约，能够降低职责混杂，也便于单独观察和替换。代价是调用次数和状态管理复杂度增加，所以主阶段由确定性 Orchestrator 控制，而不是完全让模型自由规划。

## 3. 高频追问

### Q1：你们到底有几个 Agent？

> 我不会简单说“五个 Agent”。Orchestrator 是固定编排器；Recon、Finding 和 Verification 是专业 Agent；Scan 当前走确定性 ScanPipeline；Triage 走专门 Runtime；Merge 和 Finalize 是普通方法。所以更准确的说法是“多角色 Agent 与确定性扫描阶段混合编排”。

### Q2：既然流程是串行的，为什么还说两路结果？

> “两路”描述的是数据来源，不是执行线程。一类 Finding 来自 Agent 直接源码审计，另一类来自 Scanner 再经过 Triage 研判，最后在 Merge 阶段汇合。

### Q3：为什么不让 Finding 和 Scanner 并行？

> 当前实现选择 Finding 优先串行，控制逻辑和失败处理更简单，但会增加总耗时，而且 Finding 失败会阻断后续扫描。后续如果要并行，我会让两路共享 Recon 输出后分别创建任务，使用 `asyncio.gather(return_exceptions=True)` 汇合，并为每路设置独立超时和降级，避免一条分支失败导致另一条结果丢失。

注意：这是后续设计，不是当前已经实现的事实。

### Q4：同一个模型拆成多个 Agent 有意义吗？

> 有。Agent 是模型加角色、工具、上下文、状态、预算和输出协议。即使底层模型相同，不同角色仍能实现职责隔离、工具隔离、上下文隔离和独立评估；同时架构允许未来按角色切换不同模型。

### Q5：两路结果如何去重？

> 当前使用文件路径、起始行号和漏洞类型组成确定性键。命中同一键后合并更高置信度、严重级别和更完整证据。它速度快、可测试，但对相邻行和同根因不同类型的 Finding 识别能力有限。

### Q6：“可复盘”是否等于完全重放？

> 不等于。当前能够查看历史消息、事件、工具调用和 Finding，用于故障定位与历史审查；模型和环境具有不确定性，不能保证再次执行逐 Token 得到相同结果。

### Q7：保存完整消息会不会压垮数据库？

> 当前对最高频的 Token 事件做了跳过持久化和聚合，但 Runtime 消息仍是逐条写入，长期归档、批量插入、大正文外置和消息游标分页还需要生产化改造。因此我只把它描述成基础可追踪持久化，不宣称已经完成海量高并发事件平台。

### Q8：Java 控制面是不是 Python 主链路的重写？

> 不是。Java `/api/v2` 是独立实验控制面，通过 MCP 复用 Python 安全工具面，原 Python `/api/v1` Multi-Agent 链路仍然保留。这样可以验证 Java 长任务状态机和跨语言工具边界，又不需要一次性重写已有扫描器和沙箱能力。

---

# 第二条详细解释：Finding Agent 的 ReAct Runtime

## 1. ReAct 到底是什么？

`ReAct = Reasoning and Acting`，中文是“推理与行动交替”。

普通单轮调用是：

```text
输入一段代码
→ 模型直接输出结论
```

ReAct Runtime 是：

```text
读取当前上下文
→ 模型决定下一步
→ 发起原生结构化 Tool Call
→ Runtime 校验并执行工具
→ 把 Tool Result 加入会话
→ 模型根据新证据继续
→ 最终调用 FinalizeFinding
```

代码主循环位于：

`backend/app/services/finding_runtime/query_loop.py`

它负责的不是单纯 `while` 循环，而是：

- 模型调用；
- 工具描述与选择；
- 原生 Tool Call 解析；
- 工具输入校验和权限校验；
- 工具执行结果回写；
- Continue/Terminal 状态转换；
- Nudge；
- Checkpoint；
- 最终结果提取。

### ReAct 与 Function Calling 的区别

`Function Calling / Tool Calling` 中文是“函数调用/工具调用”，解决的是：

> 模型如何用结构化参数调用一个工具。

ReAct Runtime 解决的是：

> 多轮工具调用之间如何管理状态、预算、上下文和终止。

二者不是替代关系。项目使用原生 Tool Calling 实现 ReAct 中的 Action（行动）。

## 2. 为什么不能只写一个无限循环？

不加控制的 ReAct 容易出现：

- 重复搜索同一关键词；
- 重复读取同一文件；
- 工具返回内容不断膨胀；
- 只说“我接下来继续检查”，却没有真正调用工具；
- 自然语言宣布“完成”，但没有结构化结果；
- 一直调用工具，不进入最终提交；
- 上下文超过模型窗口；
- 调用不属于当前角色的工具。

所以 Runtime 需要同时治理：

```text
工具面
+ 轮次
+ 上下文
+ 异常恢复
+ 终止协议
```

## 3. 工具权限：不是简单的一张硬编码白名单

当前实际是多层控制。

### 第一层：按 Agent 类型构建 Tool Registry

`build_runtime_tool_registry()` 会根据 `agent_type` 注册工具。

Finding 角色可以包含：

- `Read`：读取文件；
- `Glob`：匹配文件；
- `Grep`：搜索代码；
- `Write`：受 Runtime 约束的写入；
- `Bash` / `PowerShell`：环境满足时注册；
- `Skill`：加载技能；
- `FinalizeFinding`：提交最终 Finding；
- Todo、AskUser、Plan Mode 等系统工具。

Triage 角色则会额外注册：

- `GetTriageBatch`
- `GetScanFinding`
- `FinalizeTriageBatch`
- `FinalizeTriage`

代码位置：

`backend/app/services/runtime_core/runtime_tool_registry.py:586-673`

因此不能说 Finding 默认只有只读工具，也不能说它绝对没有 Shell。

### 第二层：会话 Permission Rules

`permission_rules` 可以按 Agent 类型和工具名称配置：

- `allow`：允许；
- `ask`：需要用户批准；
- `deny`：拒绝。

代码位置：

`backend/app/services/runtime_core/permission_runtime.py:20-55`

### 第三层：Plan Mode

Plan Mode（计划模式）下只允许只读工具：

```text
Read、Glob、Grep、Skill
```

其他工具会被阻止。

### 第四层：Skill `allowed_tools`

活动 Skill 可以声明 `allowed_tools`。

Runtime 会汇总活动 Skill 的允许工具：

- 如果列表非空，只允许名单中的工具；
- 如果没有配置任何 `allowed_tools`，当前逻辑是放行，而不是全部拒绝。

代码位置：

`backend/app/services/runtime_core/permission_runtime.py:82-100`

### 第五层：延迟工具激活

部分工具使用 Deferred Tool（延迟工具）机制，默认不把所有工具描述塞给模型，需要时通过 Tool Search 激活。

好处是：

- 减少工具 Schema 占用的上下文；
- 减少模型选择工具时的干扰；
- 让常用工具优先可见。

### 面试中的准确说法

> 我没有只依赖提示词告诉模型“不要调用危险工具”，而是按 Agent 类型构建工具注册表，再通过会话权限规则、Plan Mode、Skill allowed-tools 和工具自身校验做运行时拦截。白名单控制“可以选择哪个工具”，Shell 沙箱、路径限制和参数校验继续控制“工具内部能做什么”。

## 4. 预算机制：哪些是真的，哪些容易说错？

### 4.1 轮次上限

`FindingRuntimeRunner` 使用 `max_turns` 控制 QueryLoop 最多运行多少轮：

```python
while self._max_turns is None or turns_executed < self._max_turns:
```

达到上限后返回：

```text
stop_reason = max_turns
completion_mode = incomplete
```

代码位置：

`backend/app/services/finding_runtime/runner.py:45-76`

但是：

> `max_turns` 是可配置项，当前 Finding Runtime 主入口没有统一硬编码默认值；未配置时可以是 `None`。

因此简历应写“可配置轮次上限”，不要写“默认固定 N 轮”。

旧版 Finding Agent 自身还有 `max_iterations=32`，但这属于 Legacy Runtime（旧运行栈），不能与新版 QueryLoop 的 `max_turns` 混成同一个数字。

### 4.2 所谓 Token Budget 实际是字符预算

`evaluate_token_budget_continuation()` 读取：

```text
budget_chars
minimum_remaining_chars
max_turns
```

当前使用：

```python
current_chars += len(message.content)
```

代码位置：

`backend/app/services/finding_runtime/query_token_budget.py:9-45`

所以它准确的名称应该是：

> 字符级上下文预算，作为 Token 的粗略近似。

它不是：

- tokenizer 精确统计；
- 模型供应商返回的 input_tokens；
- 输入和输出 Token 的精确累计账本。

另外，这个预算来自 `runtime_state.metadata["token_budget"]`。标准 Finding Runtime 创建流程没有看到默认注入这项配置，因此它属于可配置能力，不能说每个任务默认都启用。

### 4.3 没有独立的总工具调用硬预算

新版 Python QueryLoop 会记录工具调用次数，但当前没有看到类似：

```text
max_total_tool_calls
```

的统一硬终止判断。

所以第二条不应写：

> 工具调用预算控制重复调用。

可以写：

> 轮次上限和工具结果上下文预算控制资源消耗。

重复工具调用的更强硬检测在 Java `InvestigatorAgent` 中另有实现，不能混进 Python Finding Runtime 的事实。

### 4.4 工具结果上下文预算

`apply_tool_result_budget()` 会：

1. 找出所有 `TOOL_RESULT`；
2. 计算工具结果总字符数；
3. 如果超过 `max_total_chars`；
4. 从较早的工具结果开始；
5. 将单条旧结果缩短到 `trim_to_chars`；
6. 加上 `...[truncated]` 标记。

代码位置：

`backend/app/services/finding_runtime/query_context.py:42-66`

优点：

- 规则明确；
- 不增加模型调用；
- 优先保护较新的工具结果；
- 可以控制工具输出占用。

缺点：

- 只按先后顺序，不理解哪条证据更重要；
- 只保留前缀，可能丢失文件尾部关键信息；
- 如果 `max_total_chars` 没有配置，该层直接不做任何处理；
- 它修改的是发给模型的副本，不清理数据库原文。

## 5. Nudge：运行时怎么把模型拉回流程？

`Nudge` 可以翻译为：

- 纠偏提醒；
- 轻量催促；
- 运行时引导。

它不是替模型推理，也不是直接终止，而是在模型偏离协议时追加一条合成用户消息。

### 5.1 Legacy Tool Syntax Nudge

中文：旧式文本工具语法纠偏。

模型有时会输出：

```text
Tool Call: Grep ...
Action: Read ...
```

这只是文本，不会真正执行。

Runtime 检测到后会提醒模型：

> 请改用模型供应商的原生结构化 Tool Call。

该提醒当前最多一次。

代码位置：

- `backend/app/services/finding_runtime/query_loop.py:342-380`
- `backend/app/services/finding_runtime/query_loop.py:1563-1569`

### 5.2 Continue Intent / Terminal Action Nudge

中文：继续意图/终止动作纠偏。

如果模型说：

- “让我继续查看”；
- “下一步检查某文件”；
- “我还需要分析”；

但既没有真正 Tool Call，也没有 `FinalizeFinding`，Runtime 会要求二选一：

1. 需要继续就立即调用工具；
2. 已经完成就提交结构化终止工具。

Finding Runtime Spec 中终止动作 Nudge 上限为 2：

`backend/app/services/agent_runtime/specs.py:38-54`

### 5.3 Empty Response Nudge

中文：空响应纠偏。

模型流正常结束但没有：

- 正文；
- reasoning；
- Tool Call；

Runtime 不把它视为成功，而是要求继续调用工具或提交最终结果。

### 5.4 Token Budget Continuation Nudge

中文：预算允许时继续调查提醒。

如果可选字符预算判断还有空间，并且未达到轮次限制，Runtime 可以提醒模型继续调查，而不是草率结束。

由于该预算配置默认不一定存在，不能说每个 Finding 任务都会触发这种 Nudge。

### Nudge 的优缺点

相比立即硬终止：

- 可以利用已经收集的证据；
- 给模型一次自我修正机会；
- 对偶发协议偏离更友好。

相比只写提示词：

- 触发条件由 Runtime 判断；
- 能记录 Nudge 次数和状态；
- 超过次数后可以标记 incomplete，避免无限提醒。

缺点是：

- 仍需要额外模型调用；
- 模型不保证服从；
- Nudge 写得太长会继续占用上下文；
- 次数过多会形成新的循环。

## 6. 上下文压缩的真实执行顺序

每轮调用模型前，QueryLoop 按固定顺序运行：

```text
1. get_messages_after_compact_boundary
   压缩边界过滤

2. apply_tool_result_budget
   工具结果总字符预算

3. apply_history_snip
   历史尾部保留

4. apply_microcompact
   超长工具结果微裁剪

5. apply_context_collapse_if_needed
   上下文折叠投影视图

6. auto_compact_if_needed
   自动语义摘要

7. append/prepend runtime context
   补充系统和用户上下文

8. normalize_messages_for_model
   归一化模型消息

9. evaluate_blocking_limit
   阻塞上限检查
```

代码位置：

`backend/app/services/finding_runtime/query_loop.py:109-160`

这就是“混合压缩”的真实含义：

> 不是由一个智能路由器动态选择某一种算法，而是固定流水线；每层检查自己的配置和阈值，满足条件才生效。

### 为什么按这个顺序？

从设计意图看：

1. 先移除旧压缩边界之前的内容；
2. 再执行成本低、结果确定的字符裁剪；
3. 仍然过大时才进行摘要；
4. 最后检查是否仍超过阻塞上限。

这符合：

> 先做确定性、低成本处理，再做可能丢语义的模型摘要。

但要注意，当前不是每一层默认都启用；没有配置时，多数函数会直接原样返回。

## 7. 每一种压缩具体怎么决定？

### 7.1 Compact Boundary：压缩边界过滤

`get_messages_after_compact_boundary()` 不理解消息语义，也不会判断“这段内容和那段重复”。

它只做一件事：

1. 从头扫描消息；
2. 查看 `name` 或 `metadata.kind`；
3. 找出最后一个匹配预定义边界的消息；
4. 只返回该位置之后的消息。

代码位置：

`backend/app/services/finding_runtime/query_context.py:29-39`

边界不是模型从内容中识别出来的，也不是项目开始时预先写入。

它是在真正发生压缩时，由压缩器主动创建的合成消息：

```python
content = "Auto compact boundary."
name = "auto_compact_boundary"
metadata.kind = "compact_boundary"
```

代码位置：

`backend/app/services/finding_runtime/compaction/compact.py:52-66`

中文意思是：

> “这里以前的内容已经进入一次压缩处理。”

### 7.2 History Snip：历史截尾

如果配置：

```text
keep_last_messages = N
```

系统只保留最近 N 条消息，并添加：

```text
History snip removed X earlier messages.
历史截尾移除了前面 X 条消息。
```

代码位置：

`backend/app/services/finding_runtime/query_context.py:69-82`

它速度快，但信息损失大，因此不适合在没有其他摘要的情况下随意启用。

### 7.3 Microcompact：微压缩

如果某一条工具结果超过：

```text
tool_result_max_chars
```

就保留固定长度前缀，并加：

```text
...[microcompact]
……[已微压缩]
```

代码位置：

`backend/app/services/finding_runtime/query_context.py:85-98`

它不是摘要模型，只是字符串裁剪。

### 7.4 Context Collapse：上下文折叠

如果配置了：

```text
max_chars
preserve_tail_messages
```

并且总字符数超过 `max_chars`，系统会：

1. 为消息补充 `collapse_uuid`；
2. 选择较早的一段；
3. 保留最近若干条消息；
4. 使用较早消息的前 4 条、每条最多约 70 字符构造简短摘要；
5. 在发给模型的投影视图里，用 `context_collapse_summary` 替换旧消息。

代码位置：

- `backend/app/services/finding_runtime/query_context.py:251-286`
- `backend/app/services/finding_runtime/query_context.py:319-397`

这里的摘要是确定性字符串摘要，不是 LLM 语义摘要。

### 7.5 Auto Compact：自动语义压缩

自动压缩使用模型生成总结。

压缩器会：

1. 根据 `preserve_tail_messages` 保留最近消息；
2. 对更早消息调用模型进行总结；
3. 去除以前重新注入的 Skill、工具列表等附件，避免重复摘要；
4. 创建 Boundary；
5. 创建 `auto_compact_summary`；
6. 重新注入仍需要的文件、Skill、Plan Mode、工具和 Agent 列表。

代码位置：

- `backend/app/services/finding_runtime/compaction/compact.py:22-104`
- `backend/app/services/finding_runtime/compaction/prompts.py`
- `backend/app/services/finding_runtime/compaction/post_compact.py`

摘要提示词要求保留：

- 已确认漏洞；
- source 和 sink；
- PoC 状态；
- 尚未闭合的候选；
- 已覆盖攻击面；
- 未覆盖高风险方向；
- 当前工作和待办。

它不是：

- MapReduce 摘要；
- 向量聚类摘要；
- 检索增强摘要；
- 根据漏洞重要度自动打分后选择消息。

准确说法是：

> 保留尾部消息的 LLM 滚动摘要框架，并在摘要后重新注入关键运行附件。

### 7.6 Reactive Compact：响应式压缩

如果模型返回：

- `prompt_too_long`：提示词过长；
- `image_error`：图片错误；
- `media_size`：媒体过大；

Runtime 会尝试一次响应式部分压缩：

1. 保留最近若干条消息，默认继承 `preserve_tail_messages`，缺省为 2；
2. 对较早内容做 `partial_compact_conversation(direction="up_to")`；
3. 重建边界、摘要和附件；
4. 下一轮重试。

代码位置：

`backend/app/services/finding_runtime/query_degradation.py:69-133`

如果压缩请求本身仍然 `prompt_too_long`，压缩器最多进行 3 次头部截断重试。

## 8. 自动压缩什么时候触发？

代码中的命名公式是：

```text
effective_context_window
= context_window
- min(max_output_tokens, 20_000)

auto_compact_threshold
= effective_context_window
- 13_000
```

常量位置：

`backend/app/services/finding_runtime/compaction/auto_compact.py:13-40`

如果配置：

```text
context_window = 128,000
max_output_tokens = 20,000
```

那么数值计算为：

```text
effective_context_window = 108,000
auto_compact_threshold = 95,000
```

但是当前触发量是：

```python
token_usage = sum(len(message.content or "") for message in messages)
```

也就是字符数。

所以准确结论是：

> 当前代码将字符数与 Token 命名的窗口阈值直接比较，属于近似实现且存在量纲不一致；不能把 95,000 解释成经过 tokenizer 计算的真实 95,000 Token。

### 13,000 为什么是这个值？

它是固定常量：

```python
AUTOCOMPACT_BUFFER_TOKENS = 13_000
```

当前代码和注释没有提供：

- 模型厂商依据；
- 压测结果；
- P95/P99 输入分布；
- 中文/英文 Token 比例实验；
- 不同模型动态调整公式。

因此只能说：

> 这是当前实现选用的硬编码工程安全余量。

不能说：

> 13,000 是通过严格实验得到的最优值。

另外，自动压缩失败连续达到 3 次后，会停止继续尝试，避免压缩器自身形成无限失败循环。

## 9. 自动压缩默认启用了吗？

没有足够代码证据证明标准 Finding 主入口默认启用。

原因是：

- `auto_compact_if_needed()` 需要 `autocompact_controller.context_window` 和 `max_output_tokens`；
- 其他裁剪层也需要 `query_context.pipeline` 配置；
- `AgentRuntimeAdapter.run()` 创建会话时主要写入系统提示词、Skill 和 Memory；
- 当前标准创建流程没有看到默认注入完整压缩 Pipeline。

当阈值配置不存在时：

```text
threshold <= 0
→ 不压缩
```

所以简历最安全的说法是：

> 实现可配置上下文压缩与超长请求恢复框架。

不要说：

> 所有 Finding 任务默认都会自动压缩。

## 10. 多次压缩到底压什么？

### 设计上的理想过程

理想的滚动压缩应是：

```text
第一次：
原始旧消息
→ 摘要 1 + 最近消息

第二次：
摘要 1 + 第一次以后新增消息
→ 摘要 2 + 最近消息
```

这样不会每次从数据库重新总结全部原始历史。

### 当前代码的实际问题

一次自动压缩后，QueryLoop 状态会被替换为：

```text
auto_compact_boundary
+ auto_compact_summary
+ preserved tail
+ attachments
```

代码位置：

- `backend/app/services/finding_runtime/compaction/post_compact.py:10-17`
- `backend/app/services/finding_runtime/query_loop.py:830-866`

但是默认边界集合同时包含：

```text
auto_compact_boundary
auto_compact_summary
context_collapse_summary
```

`get_messages_after_compact_boundary()` 会找到最后一个匹配项，然后返回它“之后”的消息。

因此对于：

```text
[boundary, summary, tail]
```

最后一个匹配项可能是 `summary`，返回结果就只剩：

```text
[tail]
```

摘要本身会被排除。

代码位置：

`backend/app/services/finding_runtime/query_context.py:13-39`

所以当前不能宣称：

> 多次压缩已经可靠实现“旧摘要 + 新消息”的层级滚动总结。

更准确的评价是：

> 状态替换、摘要生成和重复压缩 Tracking 已经实现，但默认边界识别把摘要也当作切片边界，存在下一轮丢失摘要的风险，需要调整边界集合或切片规则，并补充“Boundary + Summary + Tail 跨两轮”测试。

一种修正方向是：

- 只把真正的 `*_compact_boundary` 当作切片点；
- 从 Boundary 后返回时明确保留紧随其后的 Summary；
- 或让 Boundary 自身携带摘要并使用统一结构。

这是建议改进，不是当前已经完成的事实。

## 11. FinalizeFinding 如何实现“强制收口”？

`FinalizeFinding` 是特殊终止工具，不是普通自然语言答案。

代码位置：

- `backend/app/services/finding_runtime/tools/finalize_finding.py`
- `backend/app/services/finding_runtime/final_finding_contract.py`

### 11.1 Schema 校验

每个 Finding 要求包含：

- 漏洞类型和严重级别；
- 标题、描述；
- 文件和行号；
- 代码片段；
- source、sink；
- 修复建议；
- 置信度和 verdict；
- exploit_chain；
- PoC；
- impact；
- CVE justification；
- verification notes。

Pydantic 使用：

```python
ConfigDict(extra="forbid")
```

即未定义的额外字段也会被拒绝。

允许：

```json
{
  "findings": [],
  "summary": "已完成主要攻击面审计，未发现满足报告要求的漏洞。"
}
```

也就是说，结构化结束不等于必须伪造漏洞。

### 11.2 校验失败

如果字段缺失或类型错误：

- 工具返回 `finalization_rejected`；
- 返回详细 validation errors；
- 不产生 `final_payload`；
- QueryLoop 继续，让模型补齐后重试。

### 11.3 校验成功

工具返回：

```text
final_payload
completion_mode = finalize_tool
terminal_action = finalize_finding
```

QueryLoop 发现 `final_payload` 后立即进入 Terminal。

### 11.4 模型不调用 FinalizeFinding

Runtime 不会把一段“我完成了”的自然语言直接当成功。

流程是：

1. 触发最多有限次 Terminal Action Nudge；
2. 仍未提交时标记 `natural_end_without_terminal_action`；
3. `completion_mode = incomplete`；
4. Bridge 对合适的结束原因进入 Finalizer-only 恢复流程；
5. 恢复仍失败则返回 `requires_retry=true`，不把候选冒充最终成功。

所以“强制收口”的准确含义是：

> 强制结果必须经过结构化终止契约；不能保证模型一定成功提交，更不会在失败时伪造成功。

## 12. 与其他方法相比有什么好处？

### 与单轮大提示词相比

ReAct 的优势：

- 不需要一次放入整个仓库；
- 可以根据工具结果动态调整调查方向；
- 可以跨文件跟踪 source 到 sink；
- 失败位置和工具轨迹更容易观察。

代价：

- 调用次数更多；
- 延迟更高；
- 更容易出现循环；
- 必须建设预算、状态和终止控制。

### 与只靠提示词约束相比

Runtime 权限和终止契约的优势：

- 未授权工具会在执行层被拒绝；
- 自然语言“完成”不会直接改变状态；
- 结构化结果可以被后端稳定消费；
- Nudge 和失败状态可以记录。

提示词仍然有用，但它属于软约束；Runtime 校验属于硬约束。

### 与始终携带完整历史相比

组合压缩的优势：

- 先使用低成本确定性裁剪；
- 只有必要时才调用摘要模型；
- 能保留最近上下文；
- 可以在摘要后重新注入 Skill、Plan 和工具信息。

风险：

- 裁剪和摘要都可能丢证据；
- 当前字符/Token 量纲不一致；
- 默认配置未自动启用；
- 边界过滤存在摘要被排除的实现问题。

### 与自然语言最终答案相比

FinalizeFinding 的优势：

- 字段稳定；
- 可 Schema 校验；
- 可直接持久化；
- 可交给 Verification；
- 能区分成功收口和 incomplete。

但它不能：

- 自动证明漏洞一定真实；
- 消除模型幻觉；
- 替代 Verification 或人工复核。

---

# 第二条面试回答

## 1. 30 秒版本

> Finding 不是单轮问答，而是一个 ReAct Runtime。模型每轮根据已有证据选择 Read、Grep、Glob 等工具，Runtime 对工具输入和权限做校验，再把结果写回会话继续推理。为了防止模型无限调查，我加入了可配置轮次上限、字符级上下文预算、工具结果裁剪和 Nudge；结束时必须通过 FinalizeFinding 提交符合 Schema 的结构化结果，否则任务会被标记为 incomplete，而不是把自然语言当成功。

## 2. 2 分钟版本

> 我把 Finding Agent 的核心问题定义为“长程工具调用如何可控收敛”。普通 Function Calling 只能解决模型怎样调用一次工具，但不能解决多轮循环、上下文膨胀和终止状态。所以 QueryLoop 每轮先组装上下文，再让模型发起原生 Tool Call，经过 Tool Registry、会话权限、Skill allowed-tools 和工具自身校验后执行，将工具结果持久化并继续下一轮。
>
> 资源控制分几层。第一是可配置 `max_turns`，达到上限后标记 incomplete；第二是字符级上下文和工具结果预算，从旧工具结果开始裁剪；第三是可配置的组合压缩管线，包括边界过滤、History Snip、Microcompact、Context Collapse、自动模型摘要和 Prompt Too Long 时的响应式压缩。这里我会明确说明当前 Python 实现使用字符数近似 Token，而且标准入口没有默认注入完整压缩配置，这是后续需要完善的地方。
>
> 收敛方面，如果模型只说“继续检查”却没有实际调用工具，或者说“完成”但没有提交结果，Runtime 会触发有限次 Nudge。真正完成必须调用 FinalizeFinding，它通过 Pydantic Schema 校验漏洞位置、source、sink、利用链和 PoC。校验失败会继续补证据；始终不提交则返回 incomplete，不会伪装成成功。

## 3. 高频追问

### Q1：ReAct 和 Function Calling 有什么区别？

> Function Calling 是结构化工具调用协议；ReAct Runtime 管理多轮“分析—调用—观察—再分析”的循环，还负责权限、预算、上下文、恢复和终止。项目使用 Function Calling 实现 ReAct 的行动部分。

### Q2：你们真的是 Token 预算吗？

> 当前 Python Runtime 的主要实现按字符数统计，是 Token 的近似代理，不是 tokenizer 精确计数。所以我在纠错后把简历写成“字符级上下文预算”。生产化时应接入模型实际 usage 或对应 tokenizer，并统一输入、输出和摘要预留的计量单位。

### Q3：为什么迭代预算和上下文预算都需要？

> 大量小工具调用主要消耗轮次，少量超大文件结果主要消耗上下文，两种风险不同。轮次上限控制循环时间，上下文预算控制每次模型输入规模。

### Q4：有工具调用次数预算吗？

> 新版 Python Finding QueryLoop 当前主要依赖轮次上限和工具结果上下文预算，没有看到独立的总工具调用硬上限，所以我不会对外宣称已有工具调用预算。Java Investigator 另有重复调用指纹控制，但那是另一条链路。

### Q5：工具白名单在哪里生效？

> 先按 Agent 类型构建 Tool Registry，再在运行时读取会话 permission rules、Plan Mode 和活动 Skill 的 allowed-tools，工具自身还会做参数、路径或审批校验。不是只在提示词里提醒模型。

### Q6：Finding Agent 能不能调用 Shell？

> 当前 Runtime 在环境满足时可以注册 Bash 或 PowerShell，所以不能说它绝对没有 Shell。安全性还依赖权限规则、项目根目录限制、沙箱后端和工具自身校验。

### Q7：Nudge 和硬终止有什么区别？

> Nudge 是有限次软纠偏，给模型一次真正调用工具或结构化收尾的机会；超过次数仍不符合协议，就进入 incomplete 或终止状态。这样不会因为一次偶发偏离直接丢掉已有证据，也不会无限提醒。

### Q8：上下文压缩使用了哪一种算法？

> 它是固定管线，不是单一算法：先做边界过滤和确定性字符裁剪，再做 History Snip、Microcompact、Context Collapse，配置达到阈值时才做 LLM 摘要；Prompt Too Long 时还有响应式部分压缩。LLM 摘要通过专用提示词保留漏洞、source、sink、PoC 和待验证线索，不是 MapReduce 或向量聚类。

### Q9：13,000 安全缓冲怎么来的？

> 当前代码中它是固定工程常量，没有看到严格压测或不同模型动态推导，所以不能说它是最优值。生产化应该根据真实 tokenizer、模型窗口、输出预留和历史请求分布做配置化与基准测试。

### Q10：第一次压缩和第二次压缩是什么关系？

> 设计目标应是滚动摘要，而不是每次重读数据库全部原文。但当前默认边界集合把 summary 也当成边界，下一轮可能把 summary 本身切掉，所以我不会宣称多轮滚动摘要已经完全正确；这是我在代码核验中发现的待修正点。

### Q11：压缩会不会丢失漏洞证据？

> 会有风险。项目通过保留最近消息、专门的安全审计摘要提示词和摘要后附件重注入降低风险，但不能做到绝对无损。原始数据库记录通常仍保留用于历史审查；关键 Finding 证据还应尽早进入结构化状态。

### Q12：FinalizeFinding 能防止幻觉吗？

> 不能。它保证输出格式和终止状态，不保证内容真实。真实性仍依赖 source-to-sink 证据、Scanner、Verification、沙箱验证和必要的人工复核。

### Q13：模型一直不调用 FinalizeFinding 怎么办？

> Runtime 先触发有限次终止 Nudge，再尝试只暴露 FinalizeFinding 的恢复轮次。仍未提交就返回 incomplete 和 requires_retry，不会把自然语言或不完整候选包装成最终成功。

### Q14：这些机制让 Recall 提升了多少？

> 如果实验只比较 Scanner-only 与 Scanner+Agent，只能把指标提升归因于整个组合方案，不能分别归因给 Nudge、压缩或工具权限。要证明单个机制的贡献，需要做消融实验。

---

# 中英文术语对照

| 英文 | 准确中文 | 在本项目中的含义 |
|---|---|---|
| `Multi-Agent` | 多智能体、多角色 Agent | 多个拥有不同角色、工具、上下文和协议的运行单元 |
| `Orchestrator` | 编排器 | 固定安排阶段、传递数据、合并结果 |
| `Reconnaissance / Recon` | 侦察、仓库画像 | 识别技术栈、入口和高风险模块 |
| `Finding Agent` | 漏洞发现 Agent | 主动调查源码并提交漏洞候选 |
| `Finding` | 漏洞发现项、漏洞记录 | 一条结构化安全问题 |
| `ScanPipeline` | 扫描流水线 | 确定性规则扫描，不是当前主路径 Agent |
| `Triage` | 分诊、研判 | 判断扫描结果真假和优先级 |
| `Verification` | 验证 | 对候选漏洞进一步确认 |
| `Merge` | 合并去重 | 汇合直接审计和扫描分诊结果 |
| `Runtime` | 运行时 | 管理循环、状态、工具、权限、预算和终止 |
| `ReAct` | 推理与行动交替 | 分析后调用工具，再根据结果继续 |
| `Tool Calling` | 工具调用 | 模型用结构化参数请求执行工具 |
| `Tool Registry` | 工具注册表 | 当前角色可发现和调用的工具集合 |
| `Allowlist` | 允许名单、白名单 | 只允许名单中的工具或动作 |
| `Permission Rule` | 权限规则 | allow、ask、deny 等运行时决策 |
| `Plan Mode` | 计划模式 | 仅允许只读工具的受限模式 |
| `Deferred Tool` | 延迟工具 | 默认不暴露，需要时再激活 |
| `Iteration / Turn Budget` | 迭代/轮次预算 | 限制 QueryLoop 执行轮数 |
| `Token Budget` | Token 预算 | 理论上按模型 Token 控制；当前 Python 实现主要是字符近似 |
| `Tool-result Context Budget` | 工具结果上下文预算 | 防止工具输出占满模型上下文 |
| `Nudge` | 纠偏提醒、轻量催促 | 模型偏离协议时追加的合成消息 |
| `Context Compaction` | 上下文压缩 | 通过过滤、裁剪和摘要减少模型输入 |
| `Compact Boundary` | 压缩边界 | 压缩器创建的合成切片标记 |
| `History Snip` | 历史截尾 | 只保留最近若干条消息 |
| `Microcompact` | 微压缩 | 对超长工具结果做固定字符裁剪 |
| `Context Collapse` | 上下文折叠 | 用简短投影摘要替换旧消息范围 |
| `Auto Compact` | 自动压缩 | 达到阈值时调用模型总结历史 |
| `Reactive Compact` | 响应式压缩 | Prompt Too Long 等错误后的恢复压缩 |
| `Checkpoint` | 检查点 | 保存阶段或轮次状态，便于定位和恢复 |
| `Handoff` | 任务交接 | 向下一角色传递摘要、证据和建议 |
| `Structured Output` | 结构化输出 | 符合固定 Schema 的对象 |
| `FinalizeFinding` | Finding 结构化终止工具 | 提交最终漏洞数组和总结并结束 Finding 阶段 |
| `Incomplete` | 未完成 | 没有满足终止协议，不作为成功结果 |
| `Traceability` | 可追踪性 | 能定位阶段、调用和状态变化 |
| `Historical Review` | 历史复盘 | 查看过去的执行轨迹 |
| `Deterministic Replay` | 确定性重放 | 再次执行并保证相同结果；当前不保证 |
| `MCP` | 模型上下文协议 | Java 控制面调用 Python 工具面的标准协议 |
| `Control Plane` | 控制面 | 管理任务、状态、审批和流程 |
| `Tool Plane` | 工具执行面、工具计算面 | 执行扫描、搜索和沙箱验证的 Python 能力 |

---

# 面试中不要这样说

- 不要说“五个 Agent 并行执行”。
- 不要说“Scanner 也是大模型 Agent”。
- 不要说“两路分析分支互不影响”。
- 不要说“Orchestrator 让 LLM 自由决定全部主阶段”。
- 不要说“所有 Agent 强制调用同一个模型”。
- 不要说“Java 控制面已经接管 Python Multi-Agent”。
- 不要说“Java 默认一定由真实 LLM 完成漏洞挖掘”。
- 不要说“自动压缩默认对所有 Finding 会话开启”。
- 不要说“Python Runtime 精确统计 Token”。
- 不要说“95,000 就是真实 95,000 Token”。
- 不要说“13,000 是严格实验得到的最优安全值”。
- 不要说“当前多次压缩已经可靠滚动合并旧摘要”。
- 不要说“上下文压缩会删除数据库历史”。
- 不要说“FinalizeFinding 能保证漏洞真实”。
- 不要说“Nudge、压缩或白名单单独让 Recall 提升了某个数值”，除非补充消融实验。
- 不要说“当前已经完成海量高并发消息持久化”。

---

# 关键代码索引

## 第一条

1. Python Orchestrator 主顺序  
   `backend/app/services/agent/agents/orchestrator.py:683-862`

2. 两路 Finding 合并与字段规则去重  
   `backend/app/services/agent/agents/orchestrator.py:481-571`

3. 各角色 LLMService 和按角色模型覆盖  
   `backend/app/api/v1/endpoints/agent_tasks.py:1252-1337`

4. 外层任务、事件与 Finding 模型  
   `backend/app/models/agent_task.py`

5. Runtime 会话模型  
   `backend/app/models/audit_session.py`

6. 会话消息、工具调用、Checkpoint 持久化  
   `backend/app/services/finding_runtime/session_store.py`

7. 高频事件控制  
   `backend/app/services/agent/event_manager.py`

8. Java 独立工作流  
   `java-agent-service/src/main/java/com/autocve/agent/workflow/AuditWorkflow.java`

9. Java 工作流阶段  
   `java-agent-service/src/main/java/com/autocve/agent/workflow/AuditStage.java`

## 第二条

1. ReAct QueryLoop  
   `backend/app/services/finding_runtime/query_loop.py`

2. Runtime Runner 轮次上限  
   `backend/app/services/finding_runtime/runner.py`

3. 工具注册表  
   `backend/app/services/runtime_core/runtime_tool_registry.py`

4. 工具权限  
   `backend/app/services/runtime_core/permission_runtime.py`

5. 字符级预算  
   `backend/app/services/finding_runtime/query_token_budget.py`

6. 边界过滤、工具结果预算、History Snip、Microcompact、Context Collapse  
   `backend/app/services/finding_runtime/query_context.py`

7. 自动压缩触发  
   `backend/app/services/finding_runtime/compaction/auto_compact.py`

8. 自动与部分压缩  
   `backend/app/services/finding_runtime/compaction/compact.py`

9. 压缩提示词  
   `backend/app/services/finding_runtime/compaction/prompts.py`

10. 压缩后附件重建  
    `backend/app/services/finding_runtime/compaction/post_compact.py`

11. Prompt Too Long 恢复  
    `backend/app/services/finding_runtime/query_degradation.py`

12. FinalizeFinding 工具  
    `backend/app/services/finding_runtime/tools/finalize_finding.py`

13. FinalizeFinding Schema  
    `backend/app/services/finding_runtime/final_finding_contract.py`

---

# 最后建议

如果简历重点是 Agent，再突出 Java 后端，推荐项目条目顺序为：

1. Python Multi-Agent 主链路；
2. Finding ReAct Runtime；
3. Java/Spring AI + MCP 跨语言控制面；
4. Java 长任务状态机、幂等、乐观锁、Checkpoint 和启动恢复；
5. Scanner-only 与 Scanner+Agent 的整体评测结果。

其中第一、第二条讲 Agent 设计，第三、第四条讲后端工程，最后一条讲可验证成果。

指标只能归因于整个 `Scanner + Agent` 组合。没有消融实验时，不要把 Recall 或 Precision 提升单独归因给 Nudge、压缩、工具权限或 FinalizeFinding。
