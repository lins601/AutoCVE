# Java + MCP 改造 Day 2 实施记录

> 完成日期：2026-07-18  
> 对应基线：`JAVA_MCP_AGENT_REFACTOR_PLAN.md` Baseline v1  
> 状态：Day 2 已完成；未提前实现 Day 3 的扫描任务 MCP 化或 Java MCP Client。

## 已交付

- 官方 Python MCP SDK `mcp==1.28.1`，依赖范围固定为 `>=1.28.1,<2`，避免自动升级到仍在迁移期的 v2；
- FastMCP Stateless Streamable HTTP Server，协议端点为 `/mcp`，使用 JSON Response；
- `profile_repository`：确定性返回语言、框架、文件数、入口点、敏感路径、依赖清单、推荐扫描器与索引状态；
- `search_code`：关键词评分、查询意图扩展、路径/语言过滤、代码窗口、符号信息与安全指标；
- `ProjectWorkspaceService`：从 PostgreSQL 的 `project_id` 解析已物化工作区，不在 MCP 请求中克隆、解压或写入项目；
- 现有 Projects API 的相对路径校验已下沉并复用同一个 Service；
- 统一成功/失败结构、20/30 秒超时、256 KB 响应限制和超限 Artifact；
- 可重复运行的官方 MCP 客户端 Smoke Test：`backend/scripts/mcp_day2_smoke_test.py`。

## 安全与运行边界

- `project_id`、`request_id`、`trace_id`、`audit_id` 均使用统一标识符校验；
- 项目必须存在、处于激活状态，且工作区必须位于 `MANAGED_PROJECTS_ROOT` 内；
- 绝对路径、`..` 路径穿越和指向项目外部的符号链接会返回 `PERMISSION_DENIED`；
- MCP 容器中的项目目录使用只读挂载，Artifact 只能写入独立的 `/app/uploads/mcp_artifacts`；
- 单次最多发现 25,000 个文件；代码搜索最多读取 5,000 个文件、32 MB 源码，单文件最多 512 KB；
- MCP Compose 配置启用离线分析，不在工具请求中下载 tiktoken 编码或 Tree-sitter Grammar；当前容器使用 `keyword_regex`，有预装 Grammar 的环境可使用 `keyword_ast`；
- Inline MCP 响应最多 256 KB，超出后完整 JSON 保存为 Artifact，协议响应只返回摘要与 Artifact ID。

## 测试结果

### Pytest 与契约

```text
tests/mcp_tools/test_repository_read_service.py  7 passed
tests/mcp_tools/test_tools.py                    3 passed
tests/mcp_tools/test_server.py                   1 passed
tests/mcp_tools/test_contract_schema.py          1 passed

total                                           12 passed
```

覆盖内容：

- 两个 FastMCP Tool 的发现和输入 Schema；
- 真实样例仓库画像和代码搜索；
- 项目文件零写入；
- 路径穿越、绝对路径、路径不存在和符号链接逃逸；
- 项目不存在时的统一 `PROJECT_NOT_FOUND` 错误；
- 超过响应体限制后保存 Artifact；
- Python 枚举与根目录唯一 JSON Schema 一致。

受路径校验 Service 抽取影响的原 Projects API 回归测试额外通过 19 项。

### Streamable HTTP 端到端

使用官方 `ClientSession` 和 `streamable_http_client` 连接容器内 `/mcp`：

```json
{
  "tools": ["profile_repository", "search_code"],
  "profile": {
    "file_count": 554,
    "top_language": "typescript"
  },
  "search": {
    "mode": "keyword_regex",
    "result_count": 5
  }
}
```

MCP 容器和 Java Agent 容器最终均为 `healthy`。

## 运行与验证

```bash
./scripts/docker-compose-local.sh --profile java-agent build autocve-mcp-server
./scripts/docker-compose-local.sh --profile java-agent up -d autocve-mcp-server

# MCP Inspector 连接地址
# http://localhost:18082/mcp

./scripts/docker-compose-local.sh exec autocve-mcp-server \
  /app/.venv/bin/python /app/scripts/mcp_day2_smoke_test.py \
  --project-id <existing-project-id>
```

外部工具只能通过 Streamable HTTP `/mcp` 访问，不能把该端点当作普通 REST GET API。

## Day 3 起点

Day 3 只实现 `start_security_scan`、`get_scan_job`、`get_scan_findings`、ARQ 幂等提交，以及 Java Spring AI MCP Client/Gateway。不会提前加入 Agent 工作流、HITL、A2A 或 Google ADK。
