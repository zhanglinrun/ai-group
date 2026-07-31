-- P70 explicit Workspace Memory. Curator proposals are not inserted until a user confirms them.
CREATE TABLE IF NOT EXISTS workspace_memory (
  memory_id  VARCHAR(64) PRIMARY KEY,
  tenant_id  VARCHAR(64) NOT NULL,
  owner_id   VARCHAR(64) NOT NULL,
  topic      VARCHAR(128) NOT NULL,
  content    TEXT NOT NULL,
  source     VARCHAR(32) NOT NULL,
  confidence DOUBLE PRECISION NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
  revision   BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ,
  UNIQUE (tenant_id, owner_id, topic)
);

CREATE INDEX IF NOT EXISTS idx_workspace_memory_owner_updated
  ON workspace_memory (tenant_id, owner_id, updated_at DESC);
