import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";

const frontendRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = resolve(frontendRoot, "src");

test("Java MCP audit is a visible sidebar route", () => {
  const routes = readFileSync(resolve(sourceRoot, "app/routes.tsx"), "utf8");
  const sidebar = readFileSync(
    resolve(sourceRoot, "components/layout/Sidebar.tsx"),
    "utf8",
  );

  assert.match(routes, /import JavaMcpAudit from ['"]@\/pages\/JavaMcpAudit['"]/);
  assert.match(routes, /path: '\/java-mcp-audit'.*<JavaMcpAudit \/>.*visible: true/);
  assert.match(sidebar, /'\/java-mcp-audit': <Workflow/);
});

test("experiment page exposes workflow, tools, HITL and report panels", () => {
  const page = readFileSync(
    resolve(sourceRoot, "pages/JavaMcpAudit.tsx"),
    "utf8",
  );

  for (const label of [
    "工作流阶段",
    "MCP 工具调用",
    "HITL 验证审批",
    "审计发现与验证结论",
    "结构化报告",
  ]) {
    assert.match(page, new RegExp(label));
  }
  assert.match(page, /streamJavaAuditEvents/);
  assert.match(page, /decideJavaApproval/);
});

test("frontend keeps Python v1 and routes Java v2 separately", () => {
  const nginx = readFileSync(resolve(frontendRoot, "nginx.conf"), "utf8");
  const vite = readFileSync(resolve(frontendRoot, "vite.config.ts"), "utf8");

  assert.match(nginx, /location \/api\/v2\//);
  assert.match(nginx, /java-agent-service:8081/);
  assert.match(nginx, /location \/api\//);
  assert.match(nginx, /http:\/\/backend:8000\/api\//);
  assert.match(vite, /VITE_JAVA_AGENT_TARGET/);
});
