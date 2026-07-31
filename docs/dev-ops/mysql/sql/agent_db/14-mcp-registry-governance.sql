-- P60: versioned MCP registry governance metadata.
-- MySQL 8 does not support `ADD COLUMN IF NOT EXISTS`; use information_schema
-- guards so this migration remains safe to apply repeatedly.

use agent_db;

SET @p60_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE ai_client_tool_mcp ADD COLUMN protocol_version varchar(32) NOT NULL DEFAULT ''2025-03-26'' COMMENT ''MCP协议版本'' AFTER request_timeout',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_client_tool_mcp'
      AND column_name = 'protocol_version'
);
PREPARE p60_stmt FROM @p60_sql;
EXECUTE p60_stmt;
DEALLOCATE PREPARE p60_stmt;

SET @p60_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE ai_client_tool_mcp ADD COLUMN oauth_audience varchar(255) DEFAULT NULL COMMENT ''OAuth audience'' AFTER protocol_version',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_client_tool_mcp'
      AND column_name = 'oauth_audience'
);
PREPARE p60_stmt FROM @p60_sql;
EXECUTE p60_stmt;
DEALLOCATE PREPARE p60_stmt;

SET @p60_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE ai_client_tool_mcp ADD COLUMN oauth_scopes_json text COMMENT ''OAuth scopes JSON'' AFTER oauth_audience',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_client_tool_mcp'
      AND column_name = 'oauth_scopes_json'
);
PREPARE p60_stmt FROM @p60_sql;
EXECUTE p60_stmt;
DEALLOCATE PREPARE p60_stmt;

SET @p60_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE ai_client_tool_mcp ADD COLUMN allowed_domains_json text COMMENT ''出站允许域名 JSON'' AFTER oauth_scopes_json',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_client_tool_mcp'
      AND column_name = 'allowed_domains_json'
);
PREPARE p60_stmt FROM @p60_sql;
EXECUTE p60_stmt;
DEALLOCATE PREPARE p60_stmt;

SET @p60_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE ai_client_tool_mcp ADD COLUMN tool_allowlist_json text COMMENT ''MCP 工具 allowlist JSON'' AFTER allowed_domains_json',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_client_tool_mcp'
      AND column_name = 'tool_allowlist_json'
);
PREPARE p60_stmt FROM @p60_sql;
EXECUTE p60_stmt;
DEALLOCATE PREPARE p60_stmt;

SET @p60_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE ai_client_tool_mcp ADD COLUMN credential_ref varchar(255) DEFAULT NULL COMMENT ''服务端密钥引用，不保存密钥值'' AFTER tool_allowlist_json',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_client_tool_mcp'
      AND column_name = 'credential_ref'
);
PREPARE p60_stmt FROM @p60_sql;
EXECUTE p60_stmt;
DEALLOCATE PREPARE p60_stmt;

SET @p60_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE ai_client_tool_mcp ADD COLUMN version varchar(64) NOT NULL DEFAULT ''v1'' COMMENT ''管理员配置版本'' AFTER credential_ref',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_client_tool_mcp'
      AND column_name = 'version'
);
PREPARE p60_stmt FROM @p60_sql;
EXECUTE p60_stmt;
DEALLOCATE PREPARE p60_stmt;

SET @p60_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE ai_client_tool_mcp ADD COLUMN config_hash varchar(72) DEFAULT NULL COMMENT ''管理员配置SHA-256'' AFTER version',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_client_tool_mcp'
      AND column_name = 'config_hash'
);
PREPARE p60_stmt FROM @p60_sql;
EXECUTE p60_stmt;
DEALLOCATE PREPARE p60_stmt;
