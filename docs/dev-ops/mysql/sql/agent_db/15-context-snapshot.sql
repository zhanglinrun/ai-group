-- P70: recoverable, revision-pinned L2 context summary. MySQL remains the Run fact-store.
use agent_db;

CREATE TABLE IF NOT EXISTS context_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  run_id BIGINT NOT NULL,
  revision BIGINT NOT NULL,
  snapshot_json LONGTEXT NOT NULL COMMENT 'structured facts/references only; no raw prompt or hidden CoT',
  snapshot_hash VARCHAR(72) NOT NULL,
  summary_model VARCHAR(128) NOT NULL,
  summary_version VARCHAR(64) NOT NULL,
  source_hash VARCHAR(72) DEFAULT NULL,
  summary_degraded TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_context_snapshot_identity (tenant_id, owner_id, session_id, run_id),
  KEY idx_context_snapshot_owner_updated (tenant_id, owner_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P70 structured context snapshot';
