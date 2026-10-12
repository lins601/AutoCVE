import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Activity,
  AlertTriangle,
  Bot,
  CheckCircle2,
  Circle,
  Clock3,
  FileSearch,
  Fingerprint,
  Loader2,
  Play,
  RefreshCcw,
  RotateCcw,
  ShieldCheck,
  ShieldX,
  Square,
  TerminalSquare,
  Wrench,
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { api } from "@/shared/config/database";
import type { Project } from "@/shared/types";
import {
  cancelJavaAudit,
  createJavaAudit,
  decideJavaApproval,
  getJavaAudit,
  listJavaApprovals,
  retryJavaAudit,
  streamJavaAuditEvents,
  type JavaApproval,
  type JavaAuditEvent,
  type JavaAuditSnapshot,
  type JavaAuditStage,
} from "@/shared/api/javaAgentAudits";

const STAGES: Array<{ key: JavaAuditStage; label: string }> = [
  { key: "PREPARING", label: "准备仓库" },
  { key: "SCANNING", label: "工具扫描" },
  { key: "INVESTIGATING", label: "Agent 检索" },
  { key: "TRIAGING", label: "合并研判" },
  { key: "WAITING_APPROVAL", label: "人工审批" },
  { key: "VERIFYING", label: "沙箱验证" },
  { key: "REPORTING", label: "生成报告" },
  { key: "COMPLETED", label: "完成" },
];

const VULNERABILITIES = [
  ["authorization", "鉴权/越权"],
  ["injection", "注入"],
  ["logic", "业务逻辑"],
  ["secret", "敏感信息"],
  ["dependency", "依赖漏洞"],
  ["other", "其他"],
] as const;

const SCANNERS = [
  ["gitleaks", "Gitleaks"],
  ["semgrep", "Semgrep"],
  ["osv", "OSV"],
  ["trufflehog", "TruffleHog"],
] as const;

const TERMINAL_STATUSES = new Set(["COMPLETED", "FAILED", "CANCELED", "TRIAGED"]);
const STORAGE_KEY = "autocve:java-mcp-audit:last-id";

function statusTone(status?: string) {
  if (status === "COMPLETED" || status === "CONSUMED") return "bg-emerald-100 text-emerald-700";
  if (status === "FAILED" || status === "REJECTED" || status === "CANCELED") return "bg-rose-100 text-rose-700";
  if (status === "WAITING_APPROVAL" || status === "PENDING") return "bg-amber-100 text-amber-700";
  return "bg-sky-100 text-sky-700";
}

function formatTime(value?: string | null) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}

export default function JavaMcpAudit() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState("");
  const [verificationLevel, setVerificationLevel] = useState<"none" | "sandbox">("sandbox");
  const [vulnerabilities, setVulnerabilities] = useState<string[]>([
    "authorization",
    "injection",
    "secret",
  ]);
  const [scanners, setScanners] = useState<string[]>(["gitleaks"]);
  const [targetPath, setTargetPath] = useState(".");
  const [reviewer, setReviewer] = useState("security-reviewer");
  const [audit, setAudit] = useState<JavaAuditSnapshot | null>(null);
  const [approvals, setApprovals] = useState<JavaApproval[]>([]);
  const [events, setEvents] = useState<JavaAuditEvent[]>([]);
  const [starting, setStarting] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [decisionId, setDecisionId] = useState<string | null>(null);
  const [streamState, setStreamState] = useState<"idle" | "connected" | "closed" | "error">("idle");
  const lastSequence = useRef(0);

  const refresh = useCallback(async (auditId: string, quiet = false) => {
    if (!quiet) setRefreshing(true);
    try {
      const [snapshot, approvalItems] = await Promise.all([
        getJavaAudit(auditId),
        listJavaApprovals(auditId),
      ]);
      setAudit(snapshot);
      setApprovals(approvalItems);
    } catch (error) {
      if (!quiet) toast.error(error instanceof Error ? error.message : "刷新任务失败");
    } finally {
      if (!quiet) setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    api.getProjects()
      .then((items) => {
        const active = items.filter((item) => item.is_active !== false);
        setProjects(active);
        if (active.length > 0) setProjectId((current) => current || active[0].id);
      })
      .catch(() => toast.error("无法加载项目，请先确认 Python 后端已启动"));

    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) void refresh(stored, true);
  }, [refresh]);

  useEffect(() => {
    if (!audit?.id) return;
    const controller = new AbortController();
    setStreamState("connected");
    void streamJavaAuditEvents(
      audit.id,
      controller.signal,
      (event) => {
        lastSequence.current = Math.max(lastSequence.current, event.sequence);
        setEvents((current) => {
          if (current.some((item) => item.sequence === event.sequence)) return current;
          return [...current.slice(-199), event];
        });
        if (["approval.requested", "approval.approved", "approval.rejected", "audit.completed", "audit.failed"].includes(event.type)) {
          void refresh(audit.id, true);
        }
      },
      lastSequence.current,
    ).catch((error) => {
      if (!controller.signal.aborted) {
        setStreamState("error");
        console.warn("Java audit SSE closed", error);
      }
    });

    const timer = window.setInterval(() => {
      void refresh(audit.id, true);
    }, TERMINAL_STATUSES.has(audit.status) ? 5000 : 1200);

    return () => {
      controller.abort();
      window.clearInterval(timer);
      setStreamState("closed");
    };
  }, [audit?.id, refresh]);

  const startAudit = async () => {
    if (!projectId) {
      toast.error("请先选择项目");
      return;
    }
    if (scanners.length === 0 || vulnerabilities.length === 0) {
      toast.error("至少选择一个扫描器和一个漏洞方向");
      return;
    }
    setStarting(true);
    setEvents([]);
    setApprovals([]);
    lastSequence.current = 0;
    try {
      const created = await createJavaAudit({
        project_id: projectId,
        target_paths: [targetPath.trim() || "."],
        target_vulnerabilities: vulnerabilities,
        verification_level: verificationLevel,
        model_profile: "default",
        scanners,
        budgets: {
          max_iterations: 4,
          max_input_tokens: 10000,
          max_duration_seconds: 90,
        },
      });
      localStorage.setItem(STORAGE_KEY, created.id);
      setAudit(created);
      toast.success("Java MCP 审计任务已创建");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "任务创建失败");
    } finally {
      setStarting(false);
    }
  };

  const decide = async (approval: JavaApproval, decision: "approve" | "reject") => {
    if (!audit) return;
    if (!reviewer.trim()) {
      toast.error("请输入审批人");
      return;
    }
    setDecisionId(approval.id);
    try {
      await decideJavaApproval(audit.id, approval, decision, reviewer.trim());
      toast.success(decision === "approve" ? "已批准沙箱验证" : "已拒绝，任务将继续生成未验证报告");
      await refresh(audit.id, true);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "审批失败");
    } finally {
      setDecisionId(null);
    }
  };

  const toggle = (
    value: string,
    values: string[],
    setter: (next: string[]) => void,
  ) => setter(values.includes(value) ? values.filter((item) => item !== value) : [...values, value]);

  const currentStageIndex = STAGES.findIndex((item) => item.key === audit?.current_stage);
  const toolEvents = useMemo(
    () => events.filter((event) => event.type.startsWith("tool.")),
    [events],
  );
  const findings = audit?.result?.findings ?? [];
  const verifications = audit?.result?.verifications ?? [];

  return (
    <div className="space-y-5 pb-10">
      <section className="overflow-hidden rounded-[30px] border border-emerald-100 bg-[linear-gradient(135deg,#f7fbf8_0%,#eef7f1_54%,#f7f9ff_100%)] p-6 shadow-[0_24px_60px_rgba(51,92,70,0.12)] md:p-8">
        <div className="flex flex-col gap-5 xl:flex-row xl:items-center xl:justify-between">
          <div>
            <div className="mb-3 flex flex-wrap gap-2">
              <Badge className="rounded-full bg-emerald-600 text-white">实验能力 · Day 7</Badge>
              <Badge variant="outline" className="rounded-full bg-white/80">Spring AI</Badge>
              <Badge variant="outline" className="rounded-full bg-white/80">MCP</Badge>
              <Badge variant="outline" className="rounded-full bg-white/80">HITL</Badge>
            </div>
            <h1 className="text-3xl font-semibold tracking-tight text-slate-950 md:text-4xl">Java MCP 审计实验台</h1>
            <p className="mt-3 max-w-3xl text-sm leading-6 text-slate-600 md:text-base">
              用 Java 负责确定性工作流、Agent 决策与审批边界，Python MCP 负责代码检索、异步扫描和隔离验证。
            </p>
          </div>
          <div className="grid min-w-[300px] grid-cols-2 gap-3 text-sm">
            <div className="rounded-2xl border border-white bg-white/75 p-4">
              <div className="text-slate-500">当前状态</div>
              <div className="mt-1 font-semibold text-slate-900">{audit?.status ?? "尚未创建"}</div>
            </div>
            <div className="rounded-2xl border border-white bg-white/75 p-4">
              <div className="text-slate-500">事件流</div>
              <div className="mt-1 flex items-center gap-2 font-semibold text-slate-900">
                <span className={`h-2 w-2 rounded-full ${streamState === "connected" ? "bg-emerald-500" : "bg-slate-300"}`} />
                {streamState}
              </div>
            </div>
          </div>
        </div>
      </section>

      <div className="grid gap-5 xl:grid-cols-[360px_minmax(0,1fr)]">
        <Card className="rounded-[28px] border-slate-200 bg-white/90 shadow-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 font-sans normal-case tracking-normal">
              <Play className="h-5 w-5 text-emerald-600" /> 创建实验任务
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            <label className="block space-y-2 text-sm font-medium text-slate-700">
              项目
              <select
                value={projectId}
                onChange={(event) => setProjectId(event.target.value)}
                className="h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-sm outline-none focus:border-emerald-400"
              >
                <option value="">请选择项目</option>
                {projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
              </select>
            </label>

            <label className="block space-y-2 text-sm font-medium text-slate-700">
              目标路径
              <Input value={targetPath} onChange={(event) => setTargetPath(event.target.value)} placeholder="." />
            </label>

            <div>
              <div className="mb-2 text-sm font-medium text-slate-700">漏洞方向</div>
              <div className="grid grid-cols-2 gap-2">
                {VULNERABILITIES.map(([value, label]) => (
                  <label key={value} className="flex cursor-pointer items-center gap-2 rounded-xl border border-slate-200 px-3 py-2 text-sm">
                    <input type="checkbox" checked={vulnerabilities.includes(value)} onChange={() => toggle(value, vulnerabilities, setVulnerabilities)} className="accent-emerald-600" />
                    {label}
                  </label>
                ))}
              </div>
            </div>

            <div>
              <div className="mb-2 text-sm font-medium text-slate-700">扫描器</div>
              <div className="grid grid-cols-2 gap-2">
                {SCANNERS.map(([value, label]) => (
                  <label key={value} className="flex cursor-pointer items-center gap-2 rounded-xl border border-slate-200 px-3 py-2 text-sm">
                    <input type="checkbox" checked={scanners.includes(value)} onChange={() => toggle(value, scanners, setScanners)} className="accent-emerald-600" />
                    {label}
                  </label>
                ))}
              </div>
            </div>

            <label className="block space-y-2 text-sm font-medium text-slate-700">
              验证级别
              <select value={verificationLevel} onChange={(event) => setVerificationLevel(event.target.value as "none" | "sandbox")} className="h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-sm">
                <option value="sandbox">sandbox · 需要人工审批</option>
                <option value="none">none · 只读审计</option>
              </select>
            </label>

            <Button onClick={startAudit} disabled={starting} className="h-12 w-full rounded-xl bg-emerald-600 text-white hover:bg-emerald-700">
              {starting ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
              启动 Java MCP 审计
            </Button>

            {audit && (
              <div className="space-y-2 rounded-2xl bg-slate-950 p-4 font-mono text-xs text-slate-200">
                <div className="text-slate-400">AUDIT ID</div>
                <div className="break-all">{audit.id}</div>
                <div className="flex gap-2 pt-2">
                  <Button size="sm" variant="secondary" onClick={() => refresh(audit.id)} disabled={refreshing}>
                    <RefreshCcw className={`mr-1 h-3.5 w-3.5 ${refreshing ? "animate-spin" : ""}`} /> 刷新
                  </Button>
                  {!TERMINAL_STATUSES.has(audit.status) && (
                    <Button size="sm" variant="destructive" onClick={() => void cancelJavaAudit(audit.id).then(() => refresh(audit.id, true))}>
                      <Square className="mr-1 h-3.5 w-3.5" /> 取消
                    </Button>
                  )}
                  {audit.status === "FAILED" && (
                    <Button size="sm" variant="secondary" onClick={() => void retryJavaAudit(audit.id).then(() => refresh(audit.id, true))}>
                      <RotateCcw className="mr-1 h-3.5 w-3.5" /> 重试
                    </Button>
                  )}
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        <div className="space-y-5">
          <Card className="rounded-[28px] border-slate-200 bg-white/90">
            <CardHeader>
              <div className="flex items-center justify-between gap-3">
                <CardTitle className="flex items-center gap-2 font-sans normal-case tracking-normal"><Activity className="h-5 w-5 text-emerald-600" /> 工作流阶段</CardTitle>
                {audit && <span className={`rounded-full px-3 py-1 text-xs font-semibold ${statusTone(audit.status)}`}>{audit.status}</span>}
              </div>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                {STAGES.map((stage, index) => {
                  const active = audit?.current_stage === stage.key;
                  const complete = audit?.status === "COMPLETED" || index < currentStageIndex;
                  return (
                    <div key={stage.key} className={`rounded-2xl border p-3 ${active ? "border-emerald-400 bg-emerald-50" : complete ? "border-emerald-100 bg-white" : "border-slate-200 bg-slate-50"}`}>
                      <div className="flex items-center gap-2">
                        {complete ? <CheckCircle2 className="h-4 w-4 text-emerald-600" /> : active ? <Loader2 className="h-4 w-4 animate-spin text-emerald-600" /> : <Circle className="h-4 w-4 text-slate-300" />}
                        <span className="text-xs font-semibold text-slate-700">{stage.label}</span>
                      </div>
                      <div className="mt-1 font-mono text-[10px] text-slate-400">{stage.key}</div>
                    </div>
                  );
                })}
              </div>
              {audit?.error_message && (
                <div className="mt-4 flex gap-2 rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">
                  <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /> {audit.error_message}
                </div>
              )}
            </CardContent>
          </Card>

          <div className="grid gap-5 lg:grid-cols-2">
            <Card className="rounded-[28px] border-slate-200 bg-white/90">
              <CardHeader><CardTitle className="flex items-center gap-2 font-sans normal-case tracking-normal"><Wrench className="h-5 w-5 text-sky-600" /> MCP 工具调用</CardTitle></CardHeader>
              <CardContent className="max-h-[360px] space-y-2 overflow-y-auto">
                {toolEvents.length === 0 && <Empty text="等待 profile、scan、search_code 等工具事件" />}
                {toolEvents.map((event) => (
                  <div key={event.sequence} className="rounded-xl border border-slate-200 bg-slate-50 p-3">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-mono text-xs font-semibold text-slate-800">{String(event.data.tool ?? event.type)}</span>
                      <span className="text-[11px] text-slate-400">{formatTime(event.created_at)}</span>
                    </div>
                    <div className="mt-1 text-xs text-slate-500">{event.summary}</div>
                  </div>
                ))}
              </CardContent>
            </Card>

            <Card className="rounded-[28px] border-slate-200 bg-white/90">
              <CardHeader><CardTitle className="flex items-center gap-2 font-sans normal-case tracking-normal"><TerminalSquare className="h-5 w-5 text-violet-600" /> 实时事件</CardTitle></CardHeader>
              <CardContent className="max-h-[360px] space-y-2 overflow-y-auto">
                {events.length === 0 && <Empty text="创建任务后通过 SSE 实时展示阶段和 Agent 事件" />}
                {events.slice().reverse().map((event) => (
                  <div key={event.sequence} className="flex gap-3 border-b border-slate-100 pb-2 text-xs">
                    <span className="w-16 shrink-0 font-mono text-slate-400">#{event.sequence}</span>
                    <div className="min-w-0"><div className="font-semibold text-slate-700">{event.type}</div><div className="truncate text-slate-500">{event.summary}</div></div>
                  </div>
                ))}
              </CardContent>
            </Card>
          </div>
        </div>
      </div>

      <Card className="rounded-[28px] border-amber-200 bg-[linear-gradient(135deg,#fffdf6,#fff8e7)]">
        <CardHeader>
          <div className="flex flex-col justify-between gap-3 md:flex-row md:items-center">
            <CardTitle className="flex items-center gap-2 font-sans normal-case tracking-normal"><Fingerprint className="h-5 w-5 text-amber-600" /> HITL 验证审批</CardTitle>
            <Input value={reviewer} onChange={(event) => setReviewer(event.target.value)} className="max-w-xs bg-white" placeholder="审批人" />
          </div>
        </CardHeader>
        <CardContent className="grid gap-3 lg:grid-cols-2">
          {approvals.length === 0 && <Empty text="高风险 Finding 会在这里等待人工批准或拒绝" />}
          {approvals.map((approval) => (
            <div key={approval.id} className="rounded-2xl border border-amber-200 bg-white p-4">
              <div className="flex items-start justify-between gap-3">
                <div><div className="text-sm font-semibold text-slate-900">{approval.finding?.title ?? approval.finding_id}</div><div className="mt-1 text-xs text-slate-500">{approval.finding?.file_path}:{approval.finding?.line_start}</div></div>
                <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${statusTone(approval.status)}`}>{approval.status}</span>
              </div>
              <div className="mt-3 rounded-xl bg-slate-950 p-3 font-mono text-[11px] text-slate-300"><span className="text-slate-500">digest </span>{approval.action_digest.slice(0, 18)}…</div>
              {approval.status === "PENDING" && (
                <div className="mt-3 flex gap-2">
                  <Button size="sm" onClick={() => decide(approval, "approve")} disabled={decisionId === approval.id} className="bg-emerald-600 text-white hover:bg-emerald-700"><ShieldCheck className="mr-1 h-4 w-4" />批准验证</Button>
                  <Button size="sm" variant="outline" onClick={() => decide(approval, "reject")} disabled={decisionId === approval.id}><ShieldX className="mr-1 h-4 w-4" />拒绝</Button>
                </div>
              )}
              {approval.result?.verdict && <div className="mt-3 text-sm text-slate-700">验证结论：<strong>{approval.result.verdict}</strong></div>}
            </div>
          ))}
        </CardContent>
      </Card>

      <div className="grid gap-5 xl:grid-cols-[1.2fr_0.8fr]">
        <Card className="rounded-[28px] border-slate-200 bg-white/90">
          <CardHeader><CardTitle className="flex items-center gap-2 font-sans normal-case tracking-normal"><FileSearch className="h-5 w-5 text-emerald-600" /> 审计发现与验证结论</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            {findings.length === 0 && <Empty text="任务完成后展示去重 Finding、Evidence 和验证状态" />}
            {findings.map((finding, index) => (
              <div key={finding.finding_id ?? index} className="rounded-2xl border border-slate-200 p-4">
                <div className="flex flex-wrap items-start justify-between gap-2"><div><div className="font-semibold text-slate-900">{finding.title ?? "未命名 Finding"}</div><div className="mt-1 text-xs text-slate-500">{finding.file_path}:{finding.line_start} · {finding.category}</div></div><Badge variant="outline">{finding.severity ?? "medium"}</Badge></div>
                {finding.evidence?.map((item) => <div key={item} className="mt-2 rounded-xl bg-slate-50 px-3 py-2 text-xs leading-5 text-slate-600">{item}</div>)}
                {finding.verification?.verdict && <div className="mt-3 text-sm font-medium text-emerald-700">Verification: {finding.verification.verdict}</div>}
              </div>
            ))}
          </CardContent>
        </Card>

        <Card className="rounded-[28px] border-slate-200 bg-slate-950 text-slate-100">
          <CardHeader className="border-slate-800"><CardTitle className="flex items-center gap-2 font-sans normal-case tracking-normal text-white"><Bot className="h-5 w-5 text-emerald-400" /> 结构化报告</CardTitle></CardHeader>
          <CardContent>
            <div className="mb-3 grid grid-cols-3 gap-2 text-center text-xs">
              <Stat label="Findings" value={findings.length} />
              <Stat label="Verified" value={verifications.filter((item) => item.verdict && item.verdict !== "unverified").length} />
              <Stat label="Events" value={events.length} />
            </div>
            <pre className="max-h-[500px] overflow-auto whitespace-pre-wrap rounded-2xl bg-black/25 p-4 text-[11px] leading-5 text-slate-300">{audit?.result ? JSON.stringify(audit.result, null, 2) : "等待 REPORTING 阶段生成报告…"}</pre>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return <div className="col-span-full flex min-h-24 items-center justify-center rounded-2xl border border-dashed border-slate-200 bg-white/50 px-4 text-center text-sm text-slate-400"><Clock3 className="mr-2 h-4 w-4" />{text}</div>;
}

function Stat({ label, value }: { label: string; value: number }) {
  return <div className="rounded-xl bg-white/5 p-3"><div className="text-lg font-semibold text-white">{value}</div><div className="text-slate-500">{label}</div></div>;
}
