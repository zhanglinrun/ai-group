USE agent_db;

CREATE TABLE IF NOT EXISTS agent_tool_approval (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    arguments_preview JSON NOT NULL,
    estimated_microcredits BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    decision_payload JSON NULL,
    created_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tool_approval_run_call (run_id, tool_call_id),
    KEY idx_agent_tool_approval_owner_run_status (owner_id, run_id, status),
    KEY idx_agent_tool_approval_expiry (status, expires_at),
    CONSTRAINT ck_agent_tool_approval_status CHECK (
        status IN ('PENDING', 'APPROVED', 'APPROVED_ALL', 'REJECTED', 'MODIFIED', 'SKIPPED', 'TIMEOUT')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
