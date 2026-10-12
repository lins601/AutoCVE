export type JavaAuditStatus =
  | "CREATED"
  | "RUNNING"
  | "WAITING_APPROVAL"
  | "TRIAGED"
  | "COMPLETED"
  | "FAILED"
  | "CANCELED";

export type JavaAuditStage =
  | "CREATED"
  | "PREPARING"
  | "SCANNING"
  | "INVESTIGATING"
  | "TRIAGING"
  | "WAITING_APPROVAL"
  | "VERIFYING"
  | "REPORTING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELED";

export interface JavaAuditFinding {
  finding_id?: string;
  title?: string;
  category?: string;
  severity?: string;
  confidence?: number;
  file_path?: string;
  line_start?: number;
  line_end?: number;
  evidence?: string[];
  attack_path?: string[];
  needs_verification?: boolean;
  verification?: JavaVerificationResult;
  [key: string]: unknown;
}

export interface JavaVerificationArtifact {
  artifact_id: string;
  media_type: string;
  summary: string;
}

export interface JavaVerificationResult {
  approval_id?: string;
  finding_id?: string;
  verdict?: string;
  decision?: string;
  evidence?: string[];
  artifacts?: JavaVerificationArtifact[];
  execution?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface JavaAuditResult {
  findings?: JavaAuditFinding[];
  verifications?: JavaVerificationResult[];
  stats?: Record<string, unknown>;
  approval_summary?: Record<string, unknown>;
  report_status?: string;
  generated_at?: string;
  investigation?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface JavaAuditSnapshot {
  id: string;
  project_id: string;
  status: JavaAuditStatus;
  current_stage: JavaAuditStage;
  model_profile: string;
  cancel_requested: boolean;
  result?: JavaAuditResult | null;
  error_code?: string | null;
  error_message?: string | null;
  failure_stage?: JavaAuditStage | null;
  version: number;
  created_at: string;
  started_at?: string | null;
  completed_at?: string | null;
}

export interface JavaApproval {
  id: string;
  audit_id: string;
  finding_id: string;
  action_type: string;
  risk_level: string;
  action_digest: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED" | "CONSUMED";
  finding?: JavaAuditFinding | null;
  decided_by?: string | null;
  decided_at?: string | null;
  expires_at: string;
  consumed_at?: string | null;
  result?: JavaVerificationResult | null;
  version: number;
}

export interface JavaAuditEvent {
  sequence: number;
  audit_id: string;
  type: string;
  stage: JavaAuditStage;
  summary: string;
  data: Record<string, unknown>;
  created_at: string;
}

export interface CreateJavaAuditPayload {
  project_id: string;
  target_paths: string[];
  target_vulnerabilities: string[];
  verification_level: "none" | "sandbox";
  model_profile: string;
  scanners: string[];
  budgets: {
    max_iterations: number;
    max_input_tokens: number;
    max_duration_seconds: number;
  };
}

const BASE_PATH = "/api/v2/agent-audits";

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE_PATH}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const message =
      body && typeof body === "object" && "message" in body
        ? String(body.message)
        : `${response.status} ${response.statusText}`;
    throw new Error(message);
  }
  return body as T;
}

export function createJavaAudit(payload: CreateJavaAuditPayload) {
  return requestJson<JavaAuditSnapshot>("", {
    method: "POST",
    headers: { "Idempotency-Key": crypto.randomUUID() },
    body: JSON.stringify(payload),
  });
}

export function getJavaAudit(auditId: string) {
  return requestJson<JavaAuditSnapshot>(`/${encodeURIComponent(auditId)}`);
}

export function listJavaApprovals(auditId: string) {
  return requestJson<JavaApproval[]>(
    `/${encodeURIComponent(auditId)}/approvals`,
  );
}

export function decideJavaApproval(
  auditId: string,
  approval: JavaApproval,
  decision: "approve" | "reject",
  decidedBy: string,
) {
  return requestJson<JavaApproval>(
    `/${encodeURIComponent(auditId)}/approvals/${encodeURIComponent(approval.id)}/${decision}`,
    {
      method: "POST",
      body: JSON.stringify({
        action_digest: approval.action_digest,
        decided_by: decidedBy,
      }),
    },
  );
}

export function retryJavaAudit(auditId: string) {
  return requestJson<JavaAuditSnapshot>(
    `/${encodeURIComponent(auditId)}/retry`,
    { method: "POST" },
  );
}

export function cancelJavaAudit(auditId: string) {
  return requestJson<JavaAuditSnapshot>(
    `/${encodeURIComponent(auditId)}/cancel`,
    { method: "POST" },
  );
}

function parseEventBlock(block: string): JavaAuditEvent | null {
  const data = block
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("\n");
  if (!data) return null;
  try {
    return JSON.parse(data) as JavaAuditEvent;
  } catch {
    return null;
  }
}

export async function streamJavaAuditEvents(
  auditId: string,
  signal: AbortSignal,
  onEvent: (event: JavaAuditEvent) => void,
  afterSequence = 0,
): Promise<void> {
  const response = await fetch(
    `${BASE_PATH}/${encodeURIComponent(auditId)}/events`,
    {
      headers: {
        Accept: "text/event-stream",
        ...(afterSequence > 0
          ? { "Last-Event-ID": String(afterSequence) }
          : {}),
      },
      signal,
    },
  );
  if (!response.ok || !response.body) {
    throw new Error(`SSE connection failed: HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (!signal.aborted) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() ?? "";
    for (const block of blocks) {
      const event = parseEventBlock(block);
      if (event) onEvent(event);
    }
  }
}
