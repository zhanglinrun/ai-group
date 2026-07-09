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

INSERT INTO ai_client_api (api_id, base_url, api_key, completions_path, embeddings_path, status)
VALUES (
  'dev_api_001',
  'https://dashscope.aliyuncs.com/compatible-mode/v1',
  'not-configured',
  '/chat/completions',
  '/embeddings',
  1
);

INSERT INTO ai_client_model (model_id, model_name, model_type, model_usage, api_id, status)
VALUES ('dev_model_001', 'qwen-plus', 'chat', 'chat', 'dev_api_001', 1);

INSERT INTO ai_client (client_id, client_name, description, status)
VALUES ('dev_client_001', 'Dev Chat Client', 'Local dev chat client', 1);

INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, status)
VALUES ('client', 'dev_client_001', 'model', 'dev_model_001', 1);

-- 上下文记忆顾问：为固定流(chat)客户端提供多轮对话记忆，配合 FlowAgentExecuteStrategy 传入的会话ID生效
INSERT INTO ai_client_advisor (advisor_id, advisor_name, advisor_type, order_num, ext_param, status)
VALUES ('dev_advisor_memory_001', 'Dev Chat Memory', 'ChatMemory', 1, '{"maxMessages":20}', 1);

INSERT INTO ai_client_config (source_type, source_id, target_type, target_id, status)
VALUES ('client', 'dev_client_001', 'advisor', 'dev_advisor_memory_001', 1);

INSERT INTO ai_agent (agent_id, agent_name, description, channel, strategy, flow_step_count, status)
VALUES (
  'dev_role_001',
  'Dev Assistant',
  'Default fix role for local streaming chat',
  'fix',
  'flow',
  1,
  1
);

INSERT INTO ai_agent_flow_config (agent_id, client_id, client_name, client_type, sequence, step_prompt)
VALUES (
  'dev_role_001',
  'dev_client_001',
  'Dev Chat Client',
  'chat',
  1,
  'You are a helpful AI assistant. Reply concisely in the same language as the user.'
);
