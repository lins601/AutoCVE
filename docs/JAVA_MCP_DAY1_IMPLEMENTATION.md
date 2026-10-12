# Java + MCP 改造 Day 1 实施记录

> 完成日期：2026-07-18  
> 对应基线：`JAVA_MCP_AGENT_REFACTOR_PLAN.md` Baseline v1  
> 状态：Day 1 已完成；未提前实现 Day 2 的 MCP Transport 和业务工具。

## 已交付

- `java-agent-service`：Java 21 编译目标、Spring Boot 4.1.0、Spring AI 2.0.0 BOM、Spring MVC、JPA、Boot 4 Flyway Starter、PostgreSQL Driver、Actuator 和 Maven Wrapper；
- `/actuator/health`：包含 Runtime Boundary HealthIndicator，并提供 liveness/readiness 健康组；
- Java 运行边界：`AuditStage`、6 个安全工具名、8 个通用错误码、MCP 调用上下文、请求、响应、错误和 Artifact DTO；
- Python MCP 边界：`backend/app/mcp` 可在不安装 MCP SDK 的情况下导入，真实 FastMCP Server 留到 Day 2；
- 唯一契约源：`contracts/mcp/security-tools.schema.json`，覆盖公共上下文、6 个工具输入、统一响应、Artifact 和错误结构；
- Compose 占位：`java-agent-service` 与 `autocve-mcp-server` 均放入显式 `java-agent` profile，默认 AutoCVE 启动集合保持不变；
- 范围锁定：技术方案新增 Baseline v1 变更规则，第一周新增需求必须替换而不是叠加。

## 验收结果

### Java

```text
./mvnw -B test
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试覆盖：

- 启动真实 Servlet Web Server 并请求 `/actuator/health`，返回 HTTP 200 与 `UP`；
- Flyway Bean 已自动配置；
- Java 枚举与共享 JSON Schema 的工具名、错误码和公共必填字段一致；
- 工作流终态和 MCP 响应不变量。

手动启动可执行 JAR 后的健康结果：

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

### Python

```text
PYTHONPATH=backend python3 -m unittest backend.tests.mcp.test_contract_schema
Ran 1 test
OK
```

同时已通过 `compileall` 和 `import app.mcp` 检查。Python 测试读取与 Java 完全相同的根目录 Schema，没有复制第二份契约。

### Compose 与兼容性

- 本机 Docker CLI 位于 `~/.local/bin/docker`，由 Colima 提供 Docker Engine；新增 `scripts/docker-compose-local.sh`，可在非登录 Shell 的 `PATH` 不含 `~/.local/bin` 时自动定位 CLI；
- `docker compose --profile java-agent config --quiet` 已通过，Java 与 Python MCP 镜像均已实际构建；
- 既有 `db`、`redis`、`backend`、`agent-worker`、`one-click-cve-worker`、`frontend`、`sandbox`、`adminer` 均未加入 profile；
- 只有两个新增服务绑定 `java-agent` profile；
- 已执行 `docker compose --profile java-agent up -d --no-build autocve-mcp-server java-agent-service`；两个新增容器均为 `healthy`；
- Java 容器的 `http://localhost:18081/actuator/health` 返回 `{"groups":["liveness","readiness"],"status":"UP"}`；
- MCP 占位容器成功加载共享 Schema，并输出 `AutoCVE MCP Day 1 contract boundary ready`；
- Flyway 在现有 PostgreSQL 中创建独立 `agent_java` Schema，并成功应用 `V1__establish_agent_java_boundary.sql`；
- 启动过程只新增两个实验容器，未重建既有服务；验收后现有 `localhost:13000` 前端入口仍可响应。

## 运行方式

本地 Java 健康检查：

```bash
cd java-agent-service
./mvnw test
./mvnw spring-boot:run
curl http://localhost:8081/actuator/health
```

Python 契约检查：

```bash
PYTHONPATH=backend python3 -m unittest backend.tests.mcp.test_contract_schema
```

使用项目脚本构建并启用实验服务：

```bash
./scripts/docker-compose-local.sh --profile java-agent build java-agent-service autocve-mcp-server
./scripts/docker-compose-local.sh --profile java-agent up -d --no-build autocve-mcp-server java-agent-service
curl http://localhost:18081/actuator/health
```

若 Docker 已在标准 `PATH` 中，也可直接将脚本替换为 `docker compose`。不指定 `--profile java-agent` 时，新增服务不会进入默认启动集合。

## Day 2 起点

Day 2 只在现有边界内完成以下事项：引入官方 Python MCP SDK/FastMCP、启动 Streamable HTTP、实现 `profile_repository` 和 `search_code`、增加路径边界与输出大小限制。不会新增第三个 Agent、A2A、ADK 或其他工具协议。
