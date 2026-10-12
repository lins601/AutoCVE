CREATE TABLE ai_audit_runs (
    id VARCHAR(36) PRIMARY KEY,
    idempotency_key VARCHAR(128) UNIQUE,
    project_id VARCHAR(128) NOT NULL,
    python_task_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(32) NOT NULL,
    model_profile VARCHAR(128) NOT NULL,
    request_json TEXT NOT NULL,
    result_json TEXT,
    error_code VARCHAR(64),
    error_message VARCHAR(2000),
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_ai_audit_runs_project_created
    ON ai_audit_runs(project_id, created_at);

CREATE TABLE ai_audit_steps (
    id VARCHAR(36) PRIMARY KEY,
    audit_id VARCHAR(36) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    output_summary VARCHAR(2000),
    checkpoint_json TEXT,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_ai_audit_steps_run
        FOREIGN KEY (audit_id) REFERENCES ai_audit_runs(id) ON DELETE CASCADE,
    CONSTRAINT uq_ai_audit_steps_attempt UNIQUE (audit_id, stage, attempt)
);

CREATE INDEX idx_ai_audit_steps_audit_started
    ON ai_audit_steps(audit_id, started_at);

CREATE TABLE ai_tool_call_records (
    id VARCHAR(36) PRIMARY KEY,
    audit_id VARCHAR(36) NOT NULL,
    step_id VARCHAR(36),
    request_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_summary VARCHAR(2000),
    output_summary VARCHAR(2000),
    duration_ms BIGINT NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ai_tool_calls_run
        FOREIGN KEY (audit_id) REFERENCES ai_audit_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_tool_calls_step
        FOREIGN KEY (step_id) REFERENCES ai_audit_steps(id) ON DELETE SET NULL
);

CREATE INDEX idx_ai_tool_calls_audit_created
    ON ai_tool_call_records(audit_id, created_at);
