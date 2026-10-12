# Java + MCP Agent 回退说明

Java 改造是可独立关闭的增量 profile，回退不会删除原 Python Agent Runtime、项目或审计数据。

## 立即回退到原审计链路

```bash
./scripts/docker-compose-local.sh --profile java-agent stop \
  java-agent-service autocve-mcp-server mcp-scan-worker
./scripts/docker-compose-local.sh up -d --wait backend worker frontend db redis
```

继续使用 `http://localhost:13000/audit-tasks` 或“一键 CVE”即可。Nginx `/api/v1` 仍指向 Python 后端；侧栏实验入口在 Java 停止时会显示连接失败，但不会影响其他页面。

## 完全移除实验容器

```bash
./scripts/docker-compose-local.sh --profile java-agent rm -f -s \
  java-agent-service autocve-mcp-server mcp-scan-worker
```

不要执行 `down -v`，否则会删除 PostgreSQL/上传文件等共享卷。Java 数据位于 `agent_java` Schema，保留它不会影响 Python；如确需删除应先备份，并由数据库管理员单独处理。

## 故障定位顺序

1. 检查 `/actuator/health`、MCP 容器和 Worker health；
2. 关闭 `AGENT_LLM_ENABLED`，确认确定性回退链路；
3. 保留任务 ID 和脱敏日志，停止实验 profile；
4. 使用原 Python 审计入口继续业务，不在故障期间迁移或删表。
