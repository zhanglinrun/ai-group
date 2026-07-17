-- Dev seed: minimal Fix role + client/model/api/flow chain for local chat.
-- API key is patched at startup from root .env (see start-full-stack.ps1).

USE agent_db;

DELETE FROM ai_agent_flow_config WHERE agent_id = 'dev_role_001';
DELETE FROM ai_client_config WHERE source_id = 'dev_client_001' AND source_type = 'client';
DELETE FROM ai_agent WHERE agent_id = 'dev_role_001';
DELETE FROM ai_client WHERE client_id = 'dev_client_001';
DELETE FROM ai_client_model WHERE model_id = 'dev_model_001';
DELETE FROM ai_client_api WHERE api_id = 'dev_api_001';
DELETE FROM ai_client_advisor WHERE advisor_id = 'dev_advisor_memory_001';
DELETE FROM ai_client_tool_mcp WHERE mcp_id IN (
  'dev_mcp_project_knowledge_001',
  'dev_mcp_agent_utility_001'
);

INSERT INTO ai_client_api (api_id, base_url, api_key, completions_path, embeddings_path, status)
VALUES (
  'dev_api_001',
  'https://dashscope.aliyuncs.com/compatible-mode/v1',
  'not-configured',
  '/chat/completions',
  '/embeddings',
  1
);

INSERT INTO ai_client_model (model_id, model_name, model_type, model_usage, input_credits_per_million, output_credits_per_million, api_id, status)
VALUES ('dev_model_001', 'qwen-plus', 'chat', 'chat', 5, 30, 'dev_api_001', 1);

INSERT INTO ai_client (client_id, client_name, description, status)
VALUES ('dev_client_001', 'Dev Chat Client', 'Local dev chat client', 1);

INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, status)
VALUES ('client', 'dev_client_001', 'model', 'dev_model_001', 1);

-- 本地只读 MCP：由 Java MCP Client 通过 STDIO 按需启动 reactor-tool 中的 FastMCP 子进程。
-- transport_config 的顶层 key 必须与 mcp_name 对齐；request_timeout 在运行时按秒解释。
INSERT INTO ai_client_tool_mcp (
  mcp_id, mcp_name, transport_type, transport_config, request_timeout, status
)
VALUES
(
  'dev_mcp_project_knowledge_001',
  'project-knowledge',
  'stdio',
  '{"project-knowledge":{"command":"uv","args":["--directory","../reactor-tool","run","--frozen","python","-m","reactor_tool.mcp_servers.project_knowledge_server"],"env":{"PYTHONIOENCODING":"utf-8","PYTHONUNBUFFERED":"1"}}}',
  30,
  1
),
(
  'dev_mcp_agent_utility_001',
  'agent-utility',
  'stdio',
  '{"agent-utility":{"command":"uv","args":["--directory","../reactor-tool","run","--frozen","python","-m","reactor_tool.mcp_servers.agent_utility_server"],"env":{"PYTHONIOENCODING":"utf-8","PYTHONUNBUFFERED":"1"}}}',
  30,
  1
);

-- status=1 的 MCP 可进入 Agent Loop 的 run-local 工具目录和逐轮暴露策略；
-- 默认客户端绑定用于角色 profile 解析和 ToolExposurePolicy。
INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, status)
VALUES
  ('client', 'dev_client_001', 'tool_mcp', 'dev_mcp_project_knowledge_001', 1),
  ('client', 'dev_client_001', 'tool_mcp', 'dev_mcp_agent_utility_001', 1);

-- 上下文记忆顾问：为默认客户端提供多轮对话记忆，由统一 Agent Loop 注入会话上下文。
INSERT INTO ai_client_advisor (advisor_id, advisor_name, advisor_type, order_num, ext_param, status)
VALUES ('dev_advisor_memory_001', 'Dev Chat Memory', 'ChatMemory', 1, '{"maxMessages":20}', 1);

INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, status)
VALUES ('client', 'dev_client_001', 'advisor', 'dev_advisor_memory_001', 1);

INSERT INTO ai_agent (agent_id, agent_name, description, channel, status)
VALUES (
  'dev_role_001',
  '通用助手',
  '日常问答与写作助手，快速模式默认角色',
  'fix',
  1
);

INSERT INTO ai_agent_flow_config (agent_id, client_id, client_name, client_type, sequence, step_prompt)
VALUES (
  'dev_role_001',
  'dev_client_001',
  'Dev Chat Client',
  'chat',
  1,
  '你是一位乐于助人的AI助手，回答准确、简洁，默认使用与用户相同的语言作答。'
);
