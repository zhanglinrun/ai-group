-- AI 记忆与向量检索 schema（PostgreSQL + pgvector）
-- 与 MySQL 业务数据职责分离：业务事务走 MySQL，AI 记忆/向量走 PostgreSQL。
-- 由 docker-compose-platform.yml 的 postgres 服务（pgvector/pgvector:pg16）承载。

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- 关键词检索 trigram 索引

-- ============================================================================
-- 语义记忆表：承载对话记忆、摘要、文件/图片、知识块与 Data Agent schema
-- 借鉴 dodo-agentx SemanticMemoryManager 的水位线 + 增量合并机制
-- ============================================================================
CREATE TABLE IF NOT EXISTS agent_semantic_memory (
    id              VARCHAR(64)   NOT NULL PRIMARY KEY,
    owner_id        VARCHAR(64)   NOT NULL,
    doc_type        VARCHAR(32)   NOT NULL,  -- qa_pair | session_summary | cross_summary | file_chunk | image_description | knowledge_chunk | schema
    content         TEXT          NOT NULL,
    embedding       vector(1024),            -- DashScope text-embedding-v3 默认 1024 维
    metadata        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    conversation_id VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- cross_summary 的水位线：记录摘要覆盖到的最新 qa_pair.created_at
    latest_qa_created_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_asm_owner_type      ON agent_semantic_memory (owner_id, doc_type);
CREATE INDEX IF NOT EXISTS idx_asm_conversation   ON agent_semantic_memory (conversation_id) WHERE conversation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_asm_created_at      ON agent_semantic_memory (created_at);
CREATE INDEX IF NOT EXISTS idx_asm_latest_qa      ON agent_semantic_memory (latest_qa_created_at) WHERE latest_qa_created_at IS NOT NULL;
-- 关键词检索：trigram 索引支持 LIKE 模糊匹配
CREATE INDEX IF NOT EXISTS idx_asm_content_trgm   ON agent_semantic_memory USING gin (content gin_trgm_ops);
-- 向量检索索引：HNSW 适合高维近邻，余弦距离
CREATE INDEX IF NOT EXISTS idx_asm_embedding_hnsw ON agent_semantic_memory USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- ============================================================================
-- 用户画像记忆表：偏好/事实 key-value，按 (owner_id, memory_key) upsert
-- 对应 dodo-agentx 的 agent_memory（用户画像层）
-- ============================================================================
CREATE TABLE IF NOT EXISTS agent_user_profile (
    owner_id     VARCHAR(64)   NOT NULL,
    memory_key   VARCHAR(128)  NOT NULL,
    memory_type  VARCHAR(32)   NOT NULL DEFAULT 'FACT',  -- PREFERENCE | FACT | PROCEDURE
    content      TEXT          NOT NULL,
    confidence   DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    source       VARCHAR(64)   NOT NULL DEFAULT 'explicit-memory',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, memory_key)
);

CREATE INDEX IF NOT EXISTS idx_aup_owner_type ON agent_user_profile (owner_id, memory_type);
