-- P90: durable facts for citations.  This stores only source provenance and
-- verbatim excerpts; prompts, credentials, and hidden reasoning are excluded.

use agent_db;

CREATE TABLE IF NOT EXISTS evidence_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  owner_id BIGINT NOT NULL DEFAULT 0,
  run_id VARCHAR(128) NOT NULL,
  evidence_id VARCHAR(128) NOT NULL,
  source_url VARCHAR(2048) NOT NULL,
  canonical_url VARCHAR(2048) NOT NULL,
  title VARCHAR(512) NOT NULL,
  publisher VARCHAR(255) NOT NULL DEFAULT '',
  published_at DATETIME(6) NULL,
  fetched_at DATETIME(6) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  excerpt TEXT NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  reliability VARCHAR(32) NOT NULL,
  freshness VARCHAR(32) NOT NULL,
  retrieval_trace_id VARCHAR(160) NOT NULL,
  offline_fixture TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_evidence_scope_id (tenant_id, owner_id, run_id, evidence_id),
  UNIQUE KEY uk_evidence_scope_hash_url (tenant_id, owner_id, run_id, content_hash, canonical_url(255)),
  KEY idx_evidence_scope_fetched (tenant_id, owner_id, run_id, fetched_at),
  KEY idx_evidence_trace (retrieval_trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS claim_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  owner_id BIGINT NOT NULL DEFAULT 0,
  run_id VARCHAR(128) NOT NULL,
  claim_id VARCHAR(128) NOT NULL,
  statement TEXT NOT NULL,
  claim_type VARCHAR(32) NOT NULL,
  confidence DECIMAL(5,4) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_claim_scope_id (tenant_id, owner_id, run_id, claim_id),
  KEY idx_claim_scope_status (tenant_id, owner_id, run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS claim_evidence_edge (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  owner_id BIGINT NOT NULL DEFAULT 0,
  run_id VARCHAR(128) NOT NULL,
  claim_id VARCHAR(128) NOT NULL,
  evidence_id VARCHAR(128) NOT NULL,
  relation VARCHAR(16) NOT NULL,
  exact_quote TEXT NOT NULL,
  excerpt_start_offset INT NOT NULL DEFAULT 0,
  excerpt_end_offset INT NOT NULL DEFAULT 0,
  extractor_version VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_claim_evidence_scope (tenant_id, owner_id, run_id, claim_id, evidence_id),
  KEY idx_edge_evidence (tenant_id, owner_id, run_id, evidence_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
