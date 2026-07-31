-- Owner-scoped consent and retention for Agent cross-session memory.
-- Apply after 01-agent-memory.sql. Existing rows receive the configured owner retention
-- on the next preference update; until then they remain readable only to their owner.

ALTER TABLE agent_semantic_memory
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

ALTER TABLE agent_user_profile
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_asm_owner_expires_at
    ON agent_semantic_memory (owner_id, expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_aup_owner_expires_at
    ON agent_user_profile (owner_id, expires_at)
    WHERE expires_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_user_memory_preference (
    owner_id       VARCHAR(64) NOT NULL PRIMARY KEY,
    enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    retention_days INTEGER NOT NULL DEFAULT 180 CHECK (retention_days BETWEEN 1 AND 365),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
