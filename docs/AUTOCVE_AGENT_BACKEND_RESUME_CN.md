# AutoCVE Agent + 后端项目简历写法

> 生成日期：2026-07-21  
> 写作模式：mixed（Agent 为主，Java/Python 后端为辅）  
> 适用方向：Java 后端开发、AI Agent 应用开发、LLM 工程、平台后端  
> 可信度边界：内容以当前工作区源码、配置、迁移脚本、测试和评测结果为依据；当前工作区没有可用 Git 历史，因此不填写无法核验的 Commit。建议根据自己真实参与范围删减，不要把“团队已有能力”全部写成个人独立完成。

---

## 1. 最推荐的简历成稿

### 项目名称

**AutoCVE - 基于 Multi-Agent 与 MCP 的智能源码安全审计平台**

### 项目时间

按自己的真实时间填写，例如：`2025.XX - 2026.XX`

### 项目角色

**Agent / Java 后端开发**

如果主要由你独立完成修改，也可以写：

**核心开发者，负责 Agent 工作流、Java 控制面及 MCP 工具链建设**

### 技术栈

Java 21、Spring Boot、Spring AI、FastAPI、React、PostgreSQL、Redis、ARQ、MCP、Docker Sandbox、SSE、Flyway、Resilience4j、SQLAlchemy、LLM、Tree-sitter/Semgrep

### 项目简介（可直接放入简历）

面向源码安全审计场景，构建由 Python Multi-Agent 主链路和 Java Agent 控制面组成的智能审计平台，将仓库画像、规则扫描、语义调查、误报研判、人工审批、沙箱验证和报告生成串成可追踪工作流；通过 MCP 隔离 Agent 控制面与安全工具执行面，最终输出带代码证据、验证记录和审计轨迹的结构化漏洞结果。

### 负责功能 / 技术难点（Agent 优先版）

1. **重构安全审计 Multi-Agent 协作链路**：将复杂审计拆分为 Recon、Finding、Scan、Triage、Verification 等角色，由 Orchestrator 按 `Recon → Finding → ScanPipeline → Triage → Merge → Verification` 编排执行；统一持久化 Agent 消息、工具调用、Finding 和任务事件，使一次审计能够追踪到“哪个 Agent 基于什么证据得出什么结论”。

2. **落地 Finding ReAct Runtime**：采用“推理 - 调用工具 - 观察结果 - 调整下一步”的循环处理源码调查，配合结构化工具参数、角色白名单、工具预算、最终结果强制收口、Nudge 提醒和上下文压缩，避免模型只输出分析文字、不提交 Finding，或在重复搜索中无限消耗 Token。

3. **搭建 Java Agent 与 Python 工具面的 MCP 边界**：使用 Spring AI MCP Client 对接 Python FastMCP Server，抽象仓库画像、代码搜索、异步扫描、任务查询、结果读取和漏洞验证 6 个粗粒度工具；以共享 JSON Schema 约束输入输出，并设置 256 KiB 响应上限和 Artifact 大结果引用，降低跨语言接口漂移和超长上下文风险。

4. **设计可恢复的 Java Agent 状态机**：将审计建模为 `CREATED、PREPARING、SCANNING、INVESTIGATING、TRIAGING、WAITING_APPROVAL、VERIFYING、REPORTING、COMPLETED` 等状态，使用 PostgreSQL、JPA 和 Flyway 保存 Run、Step、Tool Call、Approval；结合幂等键、乐观锁、阶段 Checkpoint、启动恢复和手动重试，处理重复提交、进程重启与中途失败问题。

5. **实现高风险验证的 HITL 安全闭环**：验证前基于 Finding 与待执行参数生成 Action Digest，审批通过后签发带有效期的 HMAC Token；Python 工具端再次校验摘要、签名和 Redis 一次性消费记录，再进入无网络、只读源码、资源受限的 Docker Sandbox，防止审批内容被替换、Token 重放或 Agent 越权执行。

6. **建立 Scanner 与 Agent 的回归评测方法**：设计 10 例小型安全评测集，对比 Scanner-only 与 Scanner+Agent 两种模式；组合方案的 Recall 从 **55.56% 提升至 88.89%（+33.33 个百分点，约提升 60%）**，Precision 从 **71.43% 提升至 80.00%（+8.57 个百分点）**，FPR 从 **28.57% 降至 20.00%（下降 8.57 个百分点，约降低 30%）**，验证 Agent 对跨文件和业务语义漏洞的补充价值。

---

## 2. 如果投递 Java 后端岗位，使用这一版功能点

项目简介可以保持不变，把负责功能替换为以下 6 条：

1. **建设 Java Agent 独立控制面**：基于 Java 21、Spring Boot 和 Spring AI 提供 `/api/v2/agent-audits` 审计接口，承接任务创建、查询、取消、重试、审批和事件订阅；通过独立 Docker Compose Profile 与原 Python `/api/v1` 链路并行部署，使新增能力可以灰度验证并支持快速回退。

2. **收敛长任务状态流转**：使用显式状态机约束审计阶段和异常终点，所有阶段变化写入 Step 记录与审计事件；任务由虚拟线程异步执行，数据库保存当前阶段和 Checkpoint，服务重启后由 Recovery Coordinator 识别未完成任务并继续或重试。

3. **治理跨语言工具调用**：Java 仅持有 MCP 调用权限，不直接读取宿主源码或执行 Shell；Python FastMCP 暴露 6 个白名单工具，并通过统一响应结构、错误码、请求 ID、超时、重试和熔断隔离扫描器异常，解决 Java/Python 之间协议不一致及外部工具不稳定问题。

4. **实现异步扫描任务链路**：扫描请求写入 Redis/ARQ 后由独立 Worker 执行，Java 轮询任务状态并分页读取标准化 Finding；通过项目工作区解析、路径穿越校验、文件数量和字节数上限控制扫描范围，避免长耗时扫描阻塞 API 线程及恶意路径访问。

5. **保证审批与任务接口幂等**：任务创建使用 `Idempotency-Key` 避免客户端重试造成重复审计，Run 和 Approval 实体通过版本号处理并发更新；验证 Token 绑定审批摘要、过期时间和一次性消费状态，避免重复批准和重复执行高风险动作。

6. **完善可观测性和质量验证**：使用 SSE 推送任务阶段、Agent 调用和审批事件，并以轮询读取数据库状态作为补偿；通过 Micrometer、结构化日志、Tool Call 落库和测试覆盖工作流、MCP、HITL、恢复与脱敏，本次现场执行 **25 项 Java 测试，0 失败、0 错误，1 项外部集成测试按条件跳过**。

---

## 3. 一页简历空间不足时的精简版

### 项目简介

构建基于 Multi-Agent 与 MCP 的智能源码安全审计平台，将仓库画像、规则扫描、语义调查、误报研判、人工审批和沙箱验证串成可恢复工作流；Java/Spring AI 负责编排与状态管理，Python/FastMCP 提供受控安全工具，输出可追踪的结构化漏洞证据与报告。

### 精简功能点

- 将源码审计拆分为 Recon、Finding、Triage、Verification 等 Agent，基于 ReAct 循环、工具白名单、预算、Nudge 和上下文压缩控制推理过程，并持久化消息、工具调用和 Finding，实现审计过程可追踪、失败可恢复。
- 使用 Spring AI MCP Client 与 Python FastMCP 构建跨语言工具层，统一 6 个安全工具的 JSON Schema、错误码和 256 KiB 响应边界，以 Artifact 引用承接大结果，避免 Agent 直接获得 Shell 和宿主源码权限。
- 基于 Spring Boot、JPA、Flyway 和 PostgreSQL 实现 8 个核心阶段的审计状态机，结合幂等键、乐观锁、Checkpoint、虚拟线程和启动恢复处理重复提交、长任务异步执行与服务重启。
- 为漏洞验证加入 HITL：使用 Action Digest 绑定审批内容，HMAC Token + TTL + Redis 一次性消费防篡改和重放，并在无网络、只读、资源受限的 Docker Sandbox 中执行固定规则复核。
- 建立 10 例 Scanner/Agent 回归集，Scanner+Agent 相比 Scanner-only 将 Recall 从 55.56% 提升到 88.89%，Precision 从 71.43% 提升到 80.00%，FPR 从 28.57% 降至 20.00%。

---

## 4. 项目最终做成了什么

这个项目最终不是一个“调用一次大模型的漏洞问答页面”，而是形成了下面这套完整闭环：

1. 用户导入或选择待审计项目。
2. 系统准备受管源码工作区并生成仓库画像。
3. 规则扫描器发现已知模式问题。
4. Agent 使用搜索、读文件和扫描结果继续调查业务语义。
5. Triage 对不同来源的 Finding 做标准化、合并和误报处理。
6. 高风险验证先进入人工审批，不允许 Agent 直接执行。
7. 审批通过后，受限沙箱复核目标代码并保存 Artifact。
8. 最终结果进入漏洞管理、活动记录和报告流程。

从工程结果看，当前已经具备：

- 可运行的 Python Multi-Agent 主审计链路；
- 可独立启停的 Java + MCP 实验链路；
- 6 个跨语言 MCP 安全工具；
- 任务状态、步骤、工具调用、审批和 Finding 持久化；
- Redis 异步扫描 Worker；
- HITL 审批、摘要绑定、防重放和受限验证；
- SSE 实时进度、结构化日志和 Micrometer 指标；
- Scanner 与 Agent 的小型对比评测；
- React 操作页面、Docker Compose 部署与端到端冒烟脚本。

---

## 5. “通过什么方法，提升了什么”的准确写法

| 目标问题 | 使用的方法 | 实现效果 | 可量化结果 |
| --- | --- | --- | --- |
| 扫描器难发现跨文件、权限和业务语义漏洞 | Scanner 结果 + Agent ReAct 调查组合 | 增强语义补充发现能力 | 10 例回归集中 Recall 55.56% → 88.89% |
| 单独提升召回可能带来更多误报 | 结构化 Finding、Triage、证据要求和最终收口 | 在提高召回的同时改善准确率 | Precision 71.43% → 80.00%；FPR 28.57% → 20.00% |
| 模型容易重复调用或不输出最终结果 | 工具预算、最大迭代、Nudge、FinalizeFinding、上下文压缩 | 推理过程有上限并能强制收敛 | 有明确迭代与工具预算；不编造没有压测的耗时提升 |
| Java 与 Python 接口容易漂移 | MCP + 共享 JSON Schema + 标准错误结构 | 工具契约可测试、可替换 | 统一为 6 个业务工具，响应上限 256 KiB |
| 长耗时扫描阻塞请求 | Redis/ARQ 异步任务 + 独立 Worker + 状态轮询 | API 与扫描执行解耦 | 支持提交、查询、恢复和取消；未做并发压测，不写吞吐提升 |
| Agent 直接执行验证存在安全风险 | HITL + Digest + HMAC + TTL + Replay Guard + Docker Sandbox | 审批内容与实际动作绑定，防篡改和重放 | Token 一次性消费；沙箱无网络、源码只读 |
| 服务重启导致长任务丢失 | 状态机 + Step/Checkpoint 持久化 + Recovery Coordinator | 未完成任务可以识别并恢复 | Java 测试覆盖恢复路径，现场 25 项测试无失败 |
| 前端只靠实时连接可能漏状态 | SSE 事件 + 数据库状态轮询补偿 | 页面可以持续看到任务、Agent 和审批状态 | 后端支持 Last-Event-ID 回放；当前事件 Broker 为进程内实现 |

### 量化指标换算说明

- Recall：`88.89% - 55.56% = 33.33` 个百分点；相对提升约 `33.33 / 55.56 ≈ 60%`。
- Precision：`80.00% - 71.43% = 8.57` 个百分点；相对提升约 12%。
- FPR：`28.57% - 20.00% = 8.57` 个百分点；相对下降约 `8.57 / 28.57 ≈ 30%`。
- 这些数字来自 10 例小型回归集，适合写成“在自建 10 例回归集上”，不能写成“生产环境准确率提升 60%”。

---

## 6. 项目中采用的核心方法

### 6.1 Multi-Agent 角色拆分

不是让一个大模型一次完成所有工作，而是按安全审计职责拆分：

- Recon：理解仓库结构、语言和攻击面；
- Finding：围绕可疑代码做语义调查；
- ScanPipeline：执行确定性扫描器；
- Triage：合并结果、补证据、处理误报；
- Verification：对需要进一步确认的问题生成验证动作；
- Orchestrator：负责固定顺序、状态和结果汇总。

这样设计的原因是：每个角色的目标、工具和输出更明确，更容易限制权限、记录过程和定位错误。

### 6.2 ReAct 工具调用循环

Finding Runtime 采用 ReAct 思路：模型先判断下一步，再调用搜索或读取工具，把工具返回作为 Observation，继续决定是否调查或提交 Finding。

工程上增加了：

- 最大迭代次数；
- 工具调用预算；
- 工具参数结构化校验；
- 角色级白名单；
- 重复或停滞时的 Nudge；
- 长上下文压缩；
- 最终 `FinalizeFinding` 收口；
- Checkpoint、Memory 和 Handoff。

这部分是项目最核心的 Agent 工程内容，因为它解决的是“模型怎样长期、受控地完成任务”，而不是“怎样请求一次模型 API”。

### 6.3 确定性流程与不确定推理解耦

扫描投递、状态迁移、审批暂停、报告落库由普通代码决定；模型只负责需要语义判断的 Investigator/Finding 环节。

这样即使模型超时、格式错误或未配置账号，系统也能保留扫描结果和任务状态，并使用确定性 Fallback 做离线测试。需要准确说明：默认 Java Fallback 不会产生真正的语义增量 Finding，真实推理必须显式启用 Spring AI 模型。

### 6.4 MCP 控制面与工具面隔离

Java 控制面只决定“什么时候调用什么工具”，Python 工具面才接触仓库、扫描器和 Docker。相比向 Agent 暴露任意 Shell，6 个粗粒度工具更容易做：

- 参数校验；
- 权限控制；
- 超时与重试；
- 错误码治理；
- 调用留痕；
- 大结果转 Artifact；
- Java/Python Contract 测试。

### 6.5 HITL 与能力安全

项目没有把“人工批准”实现成普通布尔值，而是将 Finding、验证动作和审批记录计算成摘要，再签发短时 Token。执行端重新计算并验签，Redis 负责一次性消费。

这个设计可以回答面试中的安全追问：即使前端参数被修改或旧 Token 被重复提交，也无法直接执行不同的验证动作。

### 6.6 状态机、幂等与失败恢复

Java 后端用状态机收敛长任务阶段，数据库记录 Run、Step、Tool Call 和 Approval。接口重试使用幂等键，实体更新使用版本号，阶段完成保存 Checkpoint，重启时扫描未完成任务。

它解决的不是普通 CRUD 问题，而是 Agent 长任务常见的重复提交、执行到一半失败、服务重启和审批等待问题。

### 6.7 评测驱动迭代

项目用 Scanner-only 作为基线，再加入 Agent 形成 Scanner+Agent 模式，统一计算 TP、FP、FN、Precision、Recall 和 FPR。该方法让 Agent 改造不只依赖演示效果，而是能观察它究竟增加了真实发现，还是仅增加了 Token 和误报。

---

## 7. 面试时如何介绍这个项目

### 7.1 30 秒版本

我做的是一个智能源码安全审计平台，重点不是简单接入大模型，而是把审计拆成多个 Agent 和受控工具。Python 主链路负责完整审计，新增 Java/Spring AI 控制面通过 MCP 调用 Python 的仓库搜索、扫描和验证工具；任务使用状态机和 Checkpoint 保证可恢复，高风险验证必须经过 Digest、HMAC 和防重放审批。在 10 例回归集上，Scanner+Agent 将 Recall 从 55.56% 提升到 88.89%。

### 7.2 90 秒版本

传统 SAST 扫描器对已知危险 API 很有效，但遇到跨文件调用、租户权限缺失和业务语义漏洞时容易漏报，所以我把 AutoCVE 改造成 Scanner 与 Agent 协同的审计平台。Agent 侧不是单轮问答，而是由 Recon、Finding、Triage、Verification 等角色组成，Finding 使用 ReAct 循环调用搜索和读文件工具，并通过预算、Nudge、上下文压缩和 Finalize 工具控制收敛。

后端新增 Java 21 和 Spring AI 控制面，使用 MCP 调用 Python FastMCP 的 6 个安全工具。Java 负责状态机、审批、持久化和恢复，Python 负责仓库和沙箱能力，避免 Java 或模型直接接触 Shell。验证前必须人工审批，Token 绑定 Finding 和动作摘要，Python 端二次验签并用 Redis 防重放。最后我建立了 Scanner-only 与 Scanner+Agent 的 10 例回归集，Recall 提升 33.33 个百分点，同时 FPR 从 28.57% 降到 20%。

### 7.3 最容易被追问的 8 个问题

1. 为什么需要多个 Agent，而不是一个 Prompt？
2. ReAct 循环如何判断结束？模型一直调用工具怎么办？
3. Java 和 Python 为什么用 MCP，而不是普通 REST？
4. Agent、Workflow 和 Verification 分别能调用哪些工具？
5. 服务在 `WAITING_APPROVAL` 时重启，任务如何恢复？
6. Action Digest 和 HMAC Token 如何防止批准 A、执行 B？
7. Recall 提升 60% 的数据集有多大，如何避免指标夸大？
8. 当前 Java 默认 Fallback 和真实 LLM 模式有什么差别？

这些问题的详细答案可以从状态机、工具白名单、MCP Contract、审批表、Redis 重放 Key、评测集和 Investigator Decision Model 讲起。

---

## 8. 代码验证摘要

### 8.1 当前核验基线

- 本地目录：`/Users/lins/IdeaProjects/AutoCVE-main`
- Git Commit：当前工作区没有可读取的 Git 历史，无法准确填写
- 分析依据：本地源代码、`docker-compose.yml`、MCP Schema、Flyway 迁移、测试类、评测结果与验收脚本
- 现场验证：Java 25 项测试无失败；前端 Vite 生产构建成功；Python 后端源码编译通过；MCP JSON Schema 解析通过

### 8.2 Agent 关键证据

- `backend/app/services/agent/agents/orchestrator.py`：Python Agent 实际执行顺序与结果合并
- `backend/app/services/agent/agents/recon.py`：仓库侦察角色
- `backend/app/services/agent/agents/finding.py`：Finding Agent 入口
- `backend/app/services/finding_runtime/query_loop.py`：ReAct 查询循环、Nudge 和收敛
- `backend/app/services/runtime_core/tool_runtime.py`：统一工具执行与权限边界
- `backend/app/services/finding_runtime/tools/finalize_finding.py`：结构化 Finding 最终提交
- `backend/app/services/agent/agents/verification.py`：验证角色
- `skill_library/`：Agent 安全审计 Skills

### 8.3 Java 后端关键证据

- `java-agent-service/src/main/java/com/autocve/agent/api/AgentAuditController.java`
- `java-agent-service/src/main/java/com/autocve/agent/application/AuditCommandService.java`
- `java-agent-service/src/main/java/com/autocve/agent/workflow/AuditWorkflow.java`
- `java-agent-service/src/main/java/com/autocve/agent/agent/InvestigatorAgent.java`
- `java-agent-service/src/main/java/com/autocve/agent/agent/model/DeterministicInvestigatorDecisionModel.java`
- `java-agent-service/src/main/java/com/autocve/agent/mcp/gateway/SpringAiSecurityToolGateway.java`
- `java-agent-service/src/main/java/com/autocve/agent/application/ApprovalService.java`
- `java-agent-service/src/main/java/com/autocve/agent/agent/VerificationAgent.java`
- `java-agent-service/src/main/resources/db/migration/`：Java Agent 独立数据表

### 8.4 MCP、异步任务与验证证据

- `contracts/mcp/security-tools.schema.json`：6 个工具的共享 Contract
- `backend/app/mcp/server.py`：FastMCP Server
- `backend/app/mcp/tools.py`：工具实现入口
- `backend/app/services/mcp_scan_runtime/runner.py`：扫描任务运行器
- `backend/app/worker/mcp_scan_worker.py`：ARQ 扫描 Worker
- `backend/app/services/mcp_verification/service.py`：验证摘要、Token 与执行入口
- `evaluation/`：Scanner 与 Agent 评测集和结果
- `scripts/day7_full_smoke_test.py`：端到端验收条件

---

## 9. 简历中不要这样写

以下表述会造成夸大或与当前代码不符：

- 不要写“生产环境漏洞检出率提升 60%”；应写“在自建 10 例回归集上，Recall 相对提升约 60%”。
- 不要写“实现动态漏洞 PoC”；当前验证主要是受限环境中的固定规则静态复核。
- 不要写“Java 已实现完整多智能体团队”；Java 当前主要是 Investigator + Verification 和固定 Workflow。
- 不要写“Java Triage 能智能消除所有误报”；当前 Java Triage 主要是标准化、去重和验证标记。
- 不要写“Agent 默认自动发现语义漏洞”；Java 默认是确定性 Fallback，真实 LLM 需要显式开启。
- 不要写“系统支持生产级多租户安全”；Java `/api/v2` 当前缺少生产级用户身份和项目归属校验。
- 不要写“沙箱验证结果即 confirmed”；当前结果应理解为 `likely` 或 `inconclusive`。
- 不要写“支持海量并发”或具体 QPS；项目没有对应压测数据。

---

## 10. 建议简历功能点（完成对应改造后可写）

下面内容是后续增强方向，当前不能写成已经完成：

1. **生产多租户鉴权**：在 Java API Gateway 或 Spring Security 中验证 Python JWT，将 tenant/user 上下文透传到 MCP，并由 Python 工具端二次校验项目归属，补齐 `/api/v2` 的越权访问边界。
2. **持久化 Agent 事件总线**：使用 PostgreSQL Outbox、Redis Streams 或 Kafka 保存 Agent Event，以消费者位点支持服务重启和多实例回放，再将 SSE 作为面向前端的事件投影。
3. **语义 Triage 模型**：为 Java Triage 增加证据评分、来源可信度和误报原因分类，并建立独立 Precision/FPR 回归集，避免只依赖规则合并。
4. **动态验证模板**：在保持无网络、只读源码和资源限制的前提下，为特定漏洞类型构建模板化 Harness，记录可复现输入、退出码和 Artifact，将验证能力从正则复核提升到受控动态测试。
5. **容量与稳定性压测**：围绕扫描队列积压、MCP 超时、虚拟线程轮询和 SSE 多连接进行压测，补充 P95 延迟、最大并发任务数、恢复时间和降级阈值后，再把性能数据写进简历。
6. **评测集扩充**：将 10 例回归集扩展为按 CWE、语言和漏洞难度分层的数据集，引入固定种子、盲测集和回归门禁，减少小样本波动并监控 Agent Token 成本。

---

## 11. 最终使用建议

如果投递 **Agent/LLM 应用开发**，优先使用第 1 节的 6 条，顺序保持为：Multi-Agent、ReAct Runtime、MCP、状态机、HITL、评测。

如果投递 **Java 后端开发**，使用第 2 节的 6 条，重点讲：长任务状态机、异步 Worker、幂等与乐观锁、失败恢复、跨语言调用治理、可观测性。

如果简历只有一页，使用第 3 节的精简版，保留 4-5 条即可。量化结果只保留一条完整写法，不要在每条功能中重复堆数字。

最有辨识度的项目主线应该是：

> 用确定性后端流程约束不确定的模型推理，通过 MCP 工具边界、状态机、Checkpoint、HITL 和评测体系，把一次大模型问答改造成可审计、可恢复、可验证的 Agent 工程系统。
