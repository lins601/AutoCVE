# Java MCP Agent API 示例

默认 Java 地址为 `http://localhost:18081`，前端同源访问时使用 `/api/v2`。示例仅使用项目 ID，不包含模型密钥。

## 1. 创建审计

```bash
curl -sS -X POST http://localhost:18081/api/v2/agent-audits \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$(date +%s)" \
  -d '{
    "project_id": "替换为已导入的项目ID",
    "target_paths": ["."],
    "target_vulnerabilities": ["authorization", "injection", "secret"],
    "verification_level": "sandbox",
    "model_profile": "default",
    "scanners": ["gitleaks"],
    "budgets": {
      "max_iterations": 4,
      "max_input_tokens": 10000,
      "max_duration_seconds": 90
    }
  }'
```

返回 HTTP 202，记录响应中的 `id`。

## 2. 状态与 SSE

```bash
AUDIT_ID="替换为任务ID"
curl -sS "http://localhost:18081/api/v2/agent-audits/${AUDIT_ID}"
curl -N -H 'Accept: text/event-stream' \
  "http://localhost:18081/api/v2/agent-audits/${AUDIT_ID}/events"
```

SSE 使用命名事件，数据体包含 `sequence`、`type`、`stage`、`summary`、`data` 和 `created_at`。断线重连可以携带 `Last-Event-ID`。

## 3. HITL 审批

```bash
curl -sS "http://localhost:18081/api/v2/agent-audits/${AUDIT_ID}/approvals"

APPROVAL_ID="替换为审批ID"
ACTION_DIGEST="替换为审批响应中的action_digest"
curl -sS -X POST \
  "http://localhost:18081/api/v2/agent-audits/${AUDIT_ID}/approvals/${APPROVAL_ID}/approve" \
  -H 'Content-Type: application/json' \
  -d "{\"action_digest\":\"${ACTION_DIGEST}\",\"decided_by\":\"security-reviewer\"}"
```

把路径末尾改为 `/reject` 可拒绝验证。摘要不匹配、重复消费或跨 Finding 使用都会返回冲突错误。

## 4. 取消、重试与指标

```bash
curl -sS -X POST "http://localhost:18081/api/v2/agent-audits/${AUDIT_ID}/cancel"
curl -sS -X POST "http://localhost:18081/api/v2/agent-audits/${AUDIT_ID}/retry"
curl -sS http://localhost:18081/actuator/metrics
curl -sS http://localhost:18081/actuator/metrics/autocve.mcp.tool.calls
```

统一错误响应包含稳定错误码、消息、请求路径与时间，不返回 API Key、完整源码或内部堆栈。
