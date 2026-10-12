# AutoCVE 项目简历写法

> 生成日期：2026-07-18  
> 适用方向：AI 应用开发 / Agent 工程 / Java 后端  
> 内容比例：Agent 约 70%，Java 与后端约 20%，安全工具及部署约 10%  
> 使用说明：当前版本只保留自己实际参与并能讲清的内容；Java + MCP 版本必须完成对应改造和评测后再放入正式简历。

## 一、当前源码能力版

### AutoCVE——自主代码安全审计 Agent 平台

**项目时间：** 请按实际参与时间填写  
**项目角色：** 个人二次开发 / 核心开发（按真实情况选择）  
**技术栈：** ReAct、Native Function Calling、Tool Runtime、Context Engineering、Skills、Hooks、Checkpoint、LiteLLM、Tree-sitter、ChromaDB、FastAPI、Redis/ARQ、PostgreSQL、Docker、SSE

#### 项目简介

面向源代码安全审计与 CVE 挖掘场景，构建能够自主调查代码、调用检索与扫描工具并产出漏洞证据链的安全审计 Agent；围绕原生工具调用、上下文压缩、Skills、权限与检查点恢复设计自研 Runtime，使长程任务在受控沙箱中可持续执行、可追踪并可恢复。

#### 负责功能 / 技术难点

1. 自研面向长程代码审计的 **Agent Loop**，优先消费模型原生结构化 Tool Call，遇到文本伪工具语法时触发 Nudge；结合 Continue/Stop 状态、模型流重试与 `FinalizeFinding` 终止契约，避免工具未执行、循环空转和 Agent 假完成。

2. 搭建统一 **Tool Runtime**，将工具 Schema、Pydantic 参数校验、权限判断、超时与生命周期 Hook 收敛到同一链路；依据并发安全属性批量并行执行 Tool Call，并在 Shell 失败时取消同批任务，使 Observation 能够稳定回填下一轮推理。

3. 围绕长上下文设计 **Context Engineering** 管线，依次执行工具结果预算、历史裁剪、Micro-compact、Context Collapse 与自动摘要，并在压缩后重新挂载 Skills、可用工具和运行元数据，维持跨文件、多轮审计的任务连续性。

4. 扩展 **Skills、Memory、Hooks 与权限机制**，支持 Skill 正文和资源渐进式加载、模型/用户调用限制、`allowed_tools` 白名单及 Plan Mode；在 Tool Use 和 Stop 阶段注入策略，控制敏感动作并保留可审计记录。

5. 组织 Recon、Finding、Scan、Triage、Verification **专家型 Agent 工作流**，以结构化 Handoff 传递项目画像、候选漏洞与验证上下文；由确定性 Orchestrator 控制阶段顺序，兼顾多角色专业分工和审计流程稳定性。

6. 将 Tree-sitter/ChromaDB 代码检索、Semgrep 等确定性扫描器和 Docker Sandbox 注册为 Agent 工具，并用 Redis/ARQ、PostgreSQL Checkpoint 与 SSE 支撑长任务异步执行、结果验证及轨迹回放。

#### 当前版本面试定位

推荐将项目描述为：

> 自研安全审计 Agent Runtime，重点解决原生工具调用、长上下文治理、Skills 与权限控制、检查点恢复及多阶段 Agent 协作问题；后端异步任务和沙箱用于保障 Agent 可靠执行。

当前不能写入正式简历的技术：

- Claude Agent SDK；
- 已接入 MCP；
- 基于 LangGraph 构建 Agent DAG；
- Google ADK / A2A；
- 动态 Subagent 主链路。

原因：当前源码没有这些框架或协议的真实主链路调用。

## 二、推荐正式版本：完成 Java + MCP 改造后使用

### AutoCVE Nexus——基于 Spring AI 与 MCP 的智能代码安全审计平台

**项目时间：** 请按实际改造时间填写  
**项目角色：** 独立完成 Java Agent 控制面与 MCP 改造  
**技术栈：** Spring AI、MCP、Agent Workflow、Structured Output、HITL、Checkpoint、Agent Eval、Java 21、Spring Boot 4、PostgreSQL、Redis、Python/FastMCP、Tree-sitter、ChromaDB、Docker

#### 项目简介

在 AutoCVE 基础上设计 Java Agent 控制面与 Python Tool Plane 分离的混合架构，由 Spring AI 编排代码调查和漏洞验证 Agent，通过 MCP 动态发现并调用代码检索、静态扫描与沙箱工具；结合结构化输出、人工审批、检查点恢复和对照评测，构建安全可控、效果可量化的自主审计闭环。

#### 建议简历功能点（完成对应改造后可写）

1. 采用“**确定性工作流 + 双 Agent**”架构，将需求解析、状态流转和报告生成固化为 Workflow，只让 Audit Investigator 与 Verification Agent 承担动态推理；为不同节点配置工具白名单、最大步数和结构化终止结果，降低完全自治流程的不确定性。

2. 构建独立 **MCP Tool Plane**，把项目画像、代码检索、异步扫描、结果查询和漏洞验证封装为 6 个工具，通过 Streamable HTTP 完成跨语言工具发现与调用；长扫描采用“提交任务 + jobId 轮询”，配合幂等键避免 Agent 重试造成重复执行。

3. 为 Agent 增加 **HITL 与 Guardrails**，将动态验证审批绑定 `auditId + findingId + actionDigest`，用单次消费令牌阻止审批内容替换和重复执行；Verification Agent 仅在授权后调用 Docker Sandbox，并限制网络、资源和运行时间。

4. 完善 **Agent Context 与 Checkpoint**，持久化 WorkflowStep、ToolCall、模型输出、Token 消耗和运行摘要；结合阶段检查点、调用幂等记录与乐观锁，支持 Java 服务重启后从待审批或失败节点继续执行，并能够回放完整推理轨迹。

5. 建立 **Agent Eval** 体系，设计 Scanner-only 与 Scanner+Agent 对照实验，统计 Precision、Recall、误报率、验证成功率、工具成功率、Token 与耗时，并分析失败轨迹，量化 Agent 对业务逻辑漏洞发现和误报过滤的实际增益。

6. 使用 Spring Boot 实现 Agent API、SSE 事件流和运行控制面，以 PostgreSQL 保存审计状态和审批记录、Redis 承载异步任务；通过统一 Trace 串联 Java Workflow、MCP Server、ARQ Worker 与 Sandbox，补齐生产级可观测性。

## 三、压缩版：简历版面不足时使用

### 当前能力压缩版

**AutoCVE——自主代码安全审计 Agent 平台**

面向源代码安全审计与 CVE 挖掘场景，自研能够持续调查代码、调用安全工具并生成漏洞证据链的 Agent Runtime，重点解决工具调用可靠性、长上下文治理、能力按需加载和高风险动作控制问题。

- 自研原生 Function Calling 驱动的 Agent Loop，统一工具校验、权限、并行调度、超时与终止契约，约束模型完成多轮代码调查后再提交结构化漏洞结果。
- 设计 Context Engineering、Skills、Memory 与 Hooks 机制，支持历史压缩、工具结果预算、能力渐进加载、Plan Mode 和检查点恢复，维持长任务连续性。
- 将代码 RAG、静态扫描器和 Docker Sandbox 封装为 Agent 工具，以确定性 Orchestrator 组织专家型工作流，并用异步任务与 SSE 支撑执行和轨迹回放。

### Java + MCP 改造后压缩版

**AutoCVE Nexus——基于 Spring AI 与 MCP 的智能代码安全审计平台**

在 AutoCVE 基础上拆分 Java Agent 控制面与 Python Tool Plane，由 Spring AI 编排代码调查和漏洞验证 Agent，通过 MCP 动态发现并调用安全工具，以结构化输出、人工审批、检查点恢复和对照评测构建可控的自主审计闭环。

- 采用“确定性 Workflow + 双 Agent”架构，仅让 Investigator 和 Verification 承担动态推理，并以工具白名单、最大步数和结构化终止结果约束行为。
- 基于 MCP Streamable HTTP 构建跨语言 Tool Plane，通过工具发现、异步 jobId、统一错误模型与幂等键支撑 Agent 稳定调用长耗时安全能力。
- 引入 HITL、Checkpoint 与 Agent Eval，控制高风险验证动作、恢复中断任务，并量化漏洞召回、误报率、工具成功率、Token 和执行耗时。

## 四、代码验证摘要

本次已直接核验当前工作区源码，当前目录不是可识别的 Git 仓库，因此没有可提供的 commit 与拉取 manifest。正式简历中只能使用自己实际参与并能解释的部分。

已核验的主要源码依据：

- `backend/app/services/finding_runtime/query_loop.py`：原生工具调用、重试、Terminal Action 与上下文处理；
- `backend/app/services/runtime_core/tool_runtime.py`：工具校验、权限、超时与并行执行；
- `backend/app/services/agent/agents/orchestrator.py`：专家型固定审计流水线与 Handoff；
- `backend/app/services/finding_runtime/compaction/`：会话压缩和压缩后上下文恢复；
- `backend/app/services/runtime_core/skill_runtime.py`：Skill 调用和工具权限契约；
- `backend/app/services/rag/splitter.py`、`indexer.py`、`retriever.py`：AST 分块、增量索引与检索；
- `backend/app/services/llm/adapters/`：LiteLLM、Anthropic Messages 和 OpenAI Responses 适配；
- `backend/app/models/audit_session.py`：Session、Turn、Checkpoint、ToolCall、Memory 与 Artifact 持久化；
- `docker-compose.yml`：PostgreSQL、Redis、FastAPI、ARQ Worker、前端和 Sandbox 部署关系。

Java + MCP 功能目前只有技术方案，没有实现证据，详见：

- `docs/JAVA_MCP_AGENT_REFACTOR_PLAN.md`

完成对应代码、测试和评测后，才能将第二部分改成完成时态并放入正式简历。
