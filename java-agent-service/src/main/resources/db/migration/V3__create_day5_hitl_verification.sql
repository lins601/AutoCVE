ALTER TABLE ai_audit_runs
    ADD COLUMN failure_stage VARCHAR(32);

CREATE TABLE ai_approval_requests (
    id VARCHAR(36) PRIMARY KEY,
    audit_id VARCHAR(36) NOT NULL,
    finding_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    action_digest VARCHAR(64) NOT NULL,
    request_payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    decided_by VARCHAR(128),
    decided_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    result_json TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ai_approval_requests_run
        FOREIGN KEY (audit_id) REFERENCES ai_audit_runs(id) ON DELETE CASCADE,
    CONSTRAINT uq_ai_approval_action UNIQUE (audit_id, finding_id, action_digest)
);

CREATE INDEX idx_ai_approval_audit_status
    ON ai_approval_requests(audit_id, status);
