# Day 7 实现与验收记录

> 状态：已完成  
> 验收日期：2026-07-20  
> 范围：前端接入、Compose 交付、完整冒烟测试与交付文档

## 1. 交付结果

- 新增 `/java-mcp-audit` 实验入口，支持创建任务、查看工作流阶段与 SSE 事件；
- 展示 MCP 工具调用、待审批 Finding、批准/拒绝操作、验证结论、Artifact 和结构化报告；
- Nginx 将 `/api/v2` 独立代理至 Java，原 `/api` Python 链路保持不变；
- `java-agent` Compose profile 包含 Java、FastMCP、扫描 Worker、Redis、PostgreSQL 和现有服务；
- 一键脚本复用本地已验证 Java 构建缓存，避免重复下载 Maven 依赖；
- 补齐架构、API、演示和回退文档。

## 2. 启动与验收

启动：

```bash
./scripts/start-java-mcp-demo.sh
```

对已授权且能产生候选 Finding 的项目执行验收：

```bash
./scripts/day7_full_smoke_test.py --project-id "项目ID"
```

本次真实 Compose 验收结果：

```json
{
  "audit_id": "62b0ba2d-68f9-4c32-844b-1018f050f249",
  "status": "COMPLETED",
  "mcp_tools": [
    "get_scan_findings",
    "get_scan_job",
    "profile_repository",
    "search_code",
    "start_security_scan"
  ],
  "sandbox_verification": true,
  "approval_count": 2,
  "verification_artifacts": 1,
  "python_entry_healthy": true,
  "java_frontend_entry_healthy": true
}
```

该结果覆盖了确定性 MCP 编排、Investigator 自主检索、HITL 批准/拒绝、审批绑定的隔离验证和最终报告。验证结果记录运行时、网络关闭、只读工作区、退出码、Evidence 与 Artifact，不依赖 SSE 客户端是否及时消费最后一条工具事件。

## 3. 测试与健康检查

- 一键 Compose 启动成功，Java、FastMCP、扫描 Worker、前后端、Redis 和 PostgreSQL 均健康；
- 前端 Day 7 定向测试 3/3 通过，Home 回归测试 3/3 通过；
- 前端生产 Docker 镜像构建通过；
- Java `/actuator/health` 返回 `UP`，Python `/health` 可用；
- 新旧页面 `/java-mcp-audit` 与 `/audit-tasks` 均可访问；
- 端到端任务生成 2 个审批，批准 1 个、拒绝 1 个，最终生成 1 个可复核验证 Artifact。

## 4. 已有能力与边界

默认关闭真实模型并使用确定性 Investigator fallback，保证演示和测试可复现；这仍会执行 Agent 工具决策、预算控制、MCP 调用和工作流状态迁移，但不宣称产生了线上模型推理结果。设置 `AGENT_LLM_ENABLED=true` 及模型环境变量后可切换到真实 Spring AI 调用链。

`sandbox` 是按需启动的验证容器，不作为常驻健康服务；一键脚本只等待常驻服务。Java 失败时可停止 `java-agent` profile，原 Python `/api` 和 `/audit-tasks` 不受影响。
