# Day 5 实现与验收记录

> 状态：已完成  
> 验收日期：2026-07-19  
> 范围：HITL、Verification Agent、`verify_vulnerability` MCP Tool、Checkpoint 与故障恢复

## 1. 实现结果

Day 5 已把 Day 4 的确定性审计工作流扩展为带人工审批边界的验证流程：

```text
TRIAGING
  -> 为候选 Finding 创建 PENDING 审批
  -> WAITING_APPROVAL（持久化并暂停）
     -> reject  -> REPORTING（结论为 unverified）
     -> approve -> Verification Agent
                 -> verify_vulnerability MCP Tool
                 -> 一次性消费审批
                 -> 隔离沙箱执行固定验证器
                 -> 保存 JSON Artifact
                 -> REPORTING
  -> COMPLETED
```

主要落地内容：

- Flyway V3 新增 `ai_approval_requests`，并为审计任务记录失败阶段；
- 工作流新增 `WAITING_APPROVAL`、`VERIFYING`、`REPORTING` Checkpoint；
- 提供审批查询、批准、拒绝和失败任务重试 API；
- Verification Agent 只接收通过 Schema 校验后的 Finding，并只可调用 `verify_vulnerability`；
- Java 与 Python 使用同一 action digest 算法和测试向量；
- MCP 验证工具校验 HMAC 审批令牌、action digest 和绑定 Finding；
- 验证执行结果保存为可复核 JSON Artifact；
- 启动恢复器会恢复运行中、失败及等待审批的任务。

## 2. API

基础路径为 `/api/v2/agent-audits`：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/{auditId}/approvals` | 查询任务审批项 |
| `POST` | `/{auditId}/approvals/{approvalId}/approve` | 校验 action digest 后批准 |
| `POST` | `/{auditId}/approvals/{approvalId}/reject` | 拒绝验证并继续生成报告 |
| `POST` | `/{auditId}/retry` | 从已保存的失败阶段恢复 |
| `GET` | `/{auditId}` | 查询状态、失败阶段和验证结果 |
| `GET` | `/{auditId}/events` | 订阅阶段、审批和工具调用事件 |

批准或拒绝请求体必须携带服务端返回的 `action_digest`，例如：

```json
{
  "action_digest": "<64-character-sha256>",
  "decided_by": "security-reviewer"
}
```

## 3. 审批与执行安全边界

- action digest 由 action type、audit、project、Finding 和验证模式等绑定字段计算；修改任一绑定内容都会导致校验失败；
- Java 签发短期 HMAC 审批令牌，Python MCP 重新计算摘要并验证令牌，不信任调用方单方面传入的 Finding；
- Java 在副作用调用前原子消费审批，Python 再以 Redis `SET NX` 做跨进程防重放；
- `verify_vulnerability` 明确禁止网关自动重试，重复批准和重复验证均返回冲突；
- MCP 只运行内置、固定的 Python 验证器，不接受用户 shell 命令；沙箱禁网、项目目录只读，并受超时限制；
- 发往 MCP 的 Finding 被投影为契约规定的六个字段，额外字段、超长内容和非法结构会被拒绝；
- Artifact 包含审批/任务/Finding 绑定信息、输入哈希、退出码、超时状态和环境摘要，便于人工复核；
- 如果审批已消费但验证进程崩溃，系统不会自动重放有副作用的验证，而是保留可诊断状态，避免重复执行。

## 4. Checkpoint 与恢复语义

- 每个阶段开始和结束均持久化步骤；同一 Checkpoint 已完成时直接复用，不重复调用扫描器或工具；
- Java 服务启动后会扫描 `CREATED`、`RUNNING` 和 `WAITING_APPROVAL` 任务；
- 仍有有效 `PENDING` 审批的任务继续停在 `WAITING_APPROVAL`；审批均已决定或已过期后继续生成报告；
- `FAILED` 任务通过 retry API 从失败阶段恢复，并复用此前已完成的 Checkpoint；
- 乐观锁冲突映射为 HTTP 409，防止并发审批覆盖。

## 5. 配置

Docker Compose 已为 Java 和 MCP 服务注入一致的审批密钥及运行限制：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MCP_APPROVAL_HMAC_SECRET` | 仅开发占位值 | Java/MCP 共享审批签名密钥；生产必须替换为至少 32 字节的随机值 |
| `AGENT_APPROVAL_REQUEST_TTL` | `30m` | 人工审批有效期 |
| `AGENT_APPROVAL_TOKEN_TTL` | `5m` | MCP 调用令牌有效期 |
| `AGENT_VERIFICATION_TIMEOUT` | `120s` | 单次验证上限 |
| `AGENT_MAX_APPROVAL_REQUESTS` | `10` | 单任务最大审批数 |
| `REDIS_URL` | `redis://redis:6379/0` | MCP 跨进程防重放存储 |

本地启动：

```bash
./scripts/docker-compose-local.sh --profile java-agent up -d
```

健康检查：`http://127.0.0.1:18081/actuator/health`。

## 6. 自动化与 Docker 验收

完成的自动化回归：

- Java：22 个测试，0 failure、0 error，1 个可选外部 MCP 测试按条件跳过；
- Python MCP：25 个测试通过；
- Java/Python 跨语言 action digest 测试向量一致；
- Compose 配置解析通过，Java 21 镜像与 MCP 镜像构建成功；
- Java、MCP、PostgreSQL、Redis 均通过容器健康检查；
- Java 重启后，待审批任务保留原审批 ID 和 action digest，并继续处于等待状态（在审批 TTL 内）。

最终真实 Docker 冒烟测试结果：

- 批准任务 `2406234d-12e6-4e94-9233-3d21f81e1ce9` 完成；
- 错误 action digest 返回 409；
- 已批准动作只消费一次，重复批准返回 409；
- 只验证绑定的 Finding，生成 Artifact `verification-b1c2552f-6349-4621-824a-2793b055e7dd`；
- 全部拒绝的任务 `3eb6ca31-d4db-4786-92aa-3db5b8d15e4f` 仍正常完成，验证结论均为 `unverified`。

可重复执行：

```bash
python3 scripts/day5_hitl_smoke_test.py \
  --project-id <existing-project-id> \
  --scanner gitleaks \
  --timeout 240
```

## 7. 范围说明

当前验证器用于证明审批绑定、隔离执行、恢复和证据留存链路，不等同于自动利用漏洞，也不会生成或执行任意攻击命令。Artifact 下载展示、前端审批交互和完整评测属于后续 Day 6/Day 7 范围。
