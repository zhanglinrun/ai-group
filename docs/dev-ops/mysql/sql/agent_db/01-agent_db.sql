create database if not exists agent_db
  default character set utf8mb4
  default collate utf8mb4_general_ci;

use agent_db;

set names utf8mb4;

-- ai-agent 表结构初始化脚本。
-- 只包含表结构，不写入演示密钥、演示账号或外部服务凭据。

create table if not exists ai_agent (
  id bigint not null auto_increment comment '主键ID',
  agent_id varchar(64) not null comment '智能体ID',
  agent_name varchar(50) not null comment '智能体名称',
  description varchar(255) default null comment '描述',
  channel varchar(32) default null comment '渠道类型',
  strategy varchar(64) default null comment '历史角色配置元数据（不参与运行时选择）',
  flow_step_count int default null comment '角色规程步骤展示计数（不参与运行时选择）',
  status tinyint(1) default 1 comment '状态(0:禁用,1:启用)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_agent_id (agent_id)
) engine=InnoDB default charset=utf8mb4 comment='AI智能体配置表';

create table if not exists ai_agent_flow_config (
  id bigint not null auto_increment comment '主键ID',
  agent_id varchar(64) not null comment '智能体ID',
  client_id varchar(64) not null comment '客户端ID',
  client_name varchar(64) default null comment '客户端名称',
  client_type varchar(64) default null comment '客户端类型',
  sequence int not null comment '角色规程排序',
  step_prompt longtext comment '角色规程提示词',
  create_time datetime default current_timestamp comment '创建时间',
  primary key (id),
  unique key uk_agent_client_seq (agent_id, client_id, sequence)
) engine=InnoDB default charset=utf8mb4 comment='角色profile的客户端与规程绑定表';

create table if not exists ai_agent_task_schedule (
  id bigint not null auto_increment comment '主键ID',
  agent_id varchar(64) not null comment '智能体ID',
  task_name varchar(64) default null comment '任务名称',
  description varchar(255) default null comment '任务描述',
  cron_expression varchar(50) not null comment '时间表达式',
  task_param text comment '任务入参配置',
  status tinyint(1) default 1 comment '状态(0:无效,1:有效)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  key idx_agent_id (agent_id)
) engine=InnoDB default charset=utf8mb4 comment='智能体任务调度配置表';

create table if not exists ai_client (
  id bigint not null auto_increment comment '主键ID',
  client_id varchar(64) not null comment '客户端ID',
  client_name varchar(50) not null comment '客户端名称',
  description varchar(1024) default null comment '描述',
  status tinyint(1) default 1 comment '状态(0:禁用,1:启用)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_client_id (client_id)
) engine=InnoDB default charset=utf8mb4 comment='AI客户端配置表';

create table if not exists ai_client_advisor (
  id bigint not null auto_increment comment '主键ID',
  advisor_id varchar(64) not null comment '顾问ID',
  advisor_name varchar(50) not null comment '顾问名称',
  advisor_type varchar(50) not null comment '顾问类型',
  order_num int default 0 comment '顺序号',
  ext_param varchar(2048) default null comment '扩展参数配置',
  status tinyint(1) default 1 comment '状态(0:禁用,1:启用)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_advisor_id (advisor_id)
) engine=InnoDB default charset=utf8mb4 comment='顾问配置表';

create table if not exists ai_client_api (
  id bigint not null auto_increment comment '自增主键ID',
  api_id varchar(64) not null comment '全局唯一配置ID',
  base_url varchar(255) not null comment 'API基础URL',
  api_key varchar(255) not null comment 'API密钥',
  completions_path varchar(255) not null comment '补全API路径',
  embeddings_path varchar(255) not null comment '嵌入API路径',
  status tinyint not null default 1 comment '状态：0-禁用，1-启用',
  create_time datetime not null default current_timestamp comment '创建时间',
  update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_api_id (api_id),
  key idx_status (status)
) engine=InnoDB default charset=utf8mb4 comment='OpenAI API配置表';

create table if not exists ai_client_config (
  id bigint not null auto_increment comment '主键ID',
  source_type varchar(32) not null comment '源类型',
  source_id varchar(64) not null comment '源ID',
  target_type varchar(32) not null comment '目标类型',
  target_id varchar(64) not null comment '目标ID',
  ext_param varchar(1024) default null comment '扩展参数',
  status tinyint(1) default 1 comment '状态(0:禁用,1:启用)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  key idx_source_id (source_id),
  key idx_target_id (target_id)
) engine=InnoDB default charset=utf8mb4 comment='AI客户端统一关联配置表';

create table if not exists ai_client_model (
  id bigint not null auto_increment comment '主键ID',
  model_id varchar(64) not null comment '模型ID',
  model_name varchar(64) not null comment '模型名称',
  model_type varchar(64) default null comment '模型类型',
  model_usage varchar(64) default null comment '模型用途',
  input_credits_per_million bigint not null default 5 comment '每百万输入token消耗额度',
  output_credits_per_million bigint not null default 30 comment '每百万输出token消耗额度',
  api_id varchar(64) default null comment 'API配置ID',
  status tinyint(1) default 1 comment '状态(0:禁用,1:启用)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_model_id (model_id),
  key idx_api_id (api_id)
) engine=InnoDB default charset=utf8mb4 comment='AI模型配置表';

-- Existing development databases also need the billing columns. Keep this script
-- re-runnable so 02-dev-seed.sql can safely reference them without dropping model data.
set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'ai_client_model'
                 and column_name = 'input_credits_per_million') = 0,
  'alter table ai_client_model add column input_credits_per_million bigint not null default 5 comment ''每百万输入token消耗额度'' after model_usage',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'ai_client_model'
                 and column_name = 'output_credits_per_million') = 0,
  'alter table ai_client_model add column output_credits_per_million bigint not null default 30 comment ''每百万输出token消耗额度'' after input_credits_per_million',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

create table if not exists ai_client_rag_order (
  id bigint not null auto_increment comment '主键ID',
  rag_id varchar(64) not null comment 'RAG配置ID',
  rag_name varchar(128) not null comment 'RAG名称',
  knowledge_tag varchar(128) default null comment '知识库标识',
  status tinyint(1) default 1 comment '状态(0:禁用,1:启用)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_rag_id (rag_id)
) engine=InnoDB default charset=utf8mb4 comment='AI客户端RAG配置表';

create table if not exists ai_client_system_prompt (
  id bigint not null auto_increment comment '主键ID',
  prompt_id varchar(64) not null comment '提示词ID',
  prompt_name varchar(128) not null comment '提示词名称',
  prompt_content longtext comment '提示词内容',
  status tinyint(1) default 1 comment '状态(0:禁用,1:启用)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_prompt_id (prompt_id)
) engine=InnoDB default charset=utf8mb4 comment='系统提示词配置表';

create table if not exists ai_client_tool_mcp (
  id bigint not null auto_increment comment '主键ID',
  mcp_id varchar(64) not null comment 'MCP配置ID',
  mcp_name varchar(128) not null comment 'MCP名称',
  transport_type varchar(32) default null comment '传输类型',
  transport_config text comment '传输配置JSON',
  request_timeout int default null comment '请求超时时间',
  status tinyint(1) default 1 comment '状态(0:禁用,1:启用)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_mcp_id (mcp_id)
) engine=InnoDB default charset=utf8mb4 comment='MCP工具配置表';

create table if not exists dialogue_session (
  id bigint not null auto_increment comment '主键',
  session_id varchar(64) not null comment '会话ID',
  owner_id varchar(64) default null comment '登录用户ID (ownerId = userId)',
  title varchar(255) default null comment '会话标题',
  status int default null comment '会话状态',
  latest_request_id varchar(64) default null comment '最新请求ID',
  latest_query_text longtext comment '最新用户问题',
  latest_summary_text longtext comment '最新总结文本',
  run_count int default 0 comment 'run总数',
  finished_run_count int default 0 comment '已完成run数',
  failed_run_count int default 0 comment '失败run数',
  started_at datetime default null comment '会话开始时间',
  last_active_at datetime default null comment '最近活跃时间',
  create_time datetime not null default current_timestamp comment '创建时间',
  update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
  deleted int not null default 0 comment '逻辑删除标记',
  primary key (id),
  unique key uk_session_id (session_id),
  key idx_owner_active (owner_id, last_active_at),
  key idx_last_active_at (last_active_at)
) engine=InnoDB default charset=utf8mb4 comment='会话级执行摘要';

create table if not exists dialogue_run (
  id bigint not null auto_increment comment '主键',
  run_uid varchar(64) default null comment '对外稳定运行标识',
  request_id varchar(64) not null comment '单次请求ID',
  session_id varchar(64) default null comment '会话ID',
  owner_id varchar(64) default null comment '登录用户ID (ownerId = userId)',
  entry_agent varchar(64) default null comment '入口执行链',
  role_agent_id varchar(64) default null comment '本轮固定角色ID快照',
  role_agent_name varchar(128) default null comment '本轮固定角色名称快照',
  status int default null comment '运行状态',
  query_text longtext comment '用户原始问题',
  request_fingerprint varchar(64) default null comment '客户端请求稳定指纹，旧历史行允许为空',
  final_summary_text longtext comment '最终总结文本',
  llm_call_count int default 0 comment 'LLM调用次数',
  tool_call_count int default 0 comment '工具调用次数',
  artifact_count int default 0 comment '产物数量',
  prompt_tokens_total int default 0 comment 'LLM输入token总量',
  completion_tokens_total int default 0 comment 'LLM输出token总量',
  total_tokens_total int default 0 comment 'LLM token总量',
  error_code varchar(64) default null comment '失败码',
  error_msg longtext comment '失败信息',
  started_at datetime default null comment '开始时间',
  deadline_at datetime default null comment '运行绝对截止时间',
  heartbeat_at datetime default null comment '执行进程最后心跳时间',
  finished_at datetime default null comment '结束时间',
  duration_ms bigint default null comment '总耗时(毫秒)',
  create_time datetime not null default current_timestamp comment '创建时间',
  update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
  deleted int not null default 0 comment '逻辑删除标记',
  primary key (id),
  unique key uk_request_id (request_id),
  key idx_session_started (session_id, started_at),
  key idx_run_uid (run_uid),
  key idx_run_recovery (status, deadline_at, heartbeat_at)
) engine=InnoDB default charset=utf8mb4 comment='单次对话执行总账';

create table if not exists llm_invocation (
  id bigint not null auto_increment comment '主键',
  run_id bigint not null comment '所属run',
  invocation_seq int default null comment 'run内递增序号',
  agent_name varchar(64) default null comment '当前agent名称',
  step_no int default null comment '当前步号',
  call_kind varchar(32) default null comment '调用类型',
  streaming int default null comment '是否流式',
  model_name varchar(128) default null comment '模型名',
  response_text longtext comment '完整响应文本',
  tool_call_count int default 0 comment '工具调用数量',
  prompt_tokens int default 0 comment 'prompt token',
  completion_tokens int default 0 comment 'completion token',
  total_tokens int default 0 comment 'total token',
  input_rate_snapshot bigint default null comment '输入费率快照(额度/百万token)',
  output_rate_snapshot bigint default null comment '输出费率快照(额度/百万token)',
  usage_source varchar(16) default null comment 'PROVIDER或ESTIMATED',
  charged_microcredits bigint default 0 comment '实扣微额度',
  finish_reason varchar(64) default null comment '完成原因',
  status int default null comment '状态',
  error_msg longtext comment '错误信息',
  started_at datetime default null comment '开始时间',
  finished_at datetime default null comment '结束时间',
  duration_ms bigint default null comment '耗时(毫秒)',
  create_time datetime not null default current_timestamp comment '创建时间',
  update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
  deleted int not null default 0 comment '逻辑删除标记',
  primary key (id),
  key idx_run_seq (run_id, invocation_seq)
) engine=InnoDB default charset=utf8mb4 comment='单次LLM调用账本';

create table if not exists quota_settlement_command (
  id bigint not null auto_increment comment '主键',
  user_id bigint not null comment '额度账户用户',
  billing_request_id varchar(64) not null comment 'member侧稳定幂等键',
  request_fingerprint varchar(64) not null comment '预扣不可变载荷指纹',
  ability_code varchar(64) not null comment '计费能力',
  requested_microcredits bigint not null comment '请求预扣上限',
  minimum_microcredits bigint not null comment '最小可接受预扣',
  freeze_id varchar(64) default null comment 'member冻结标识',
  reserved_microcredits bigint not null default 0 comment '实际冻结额度',
  intended_action varchar(16) not null default 'NONE' comment 'NONE/CONFIRM/RELEASE',
  intended_microcredits bigint not null default 0 comment '期望结算额度',
  settled_microcredits bigint not null default 0 comment '远端已结算额度',
  llm_invocation_id bigint default null comment '来源LLM调用账本',
  input_rate_snapshot bigint default null comment '输入费率快照',
  output_rate_snapshot bigint default null comment '输出费率快照',
  prompt_tokens int default null comment '结算输入token',
  completion_tokens int default null comment '结算输出token',
  usage_source varchar(32) default null comment 'PROVIDER/MIXED/ESTIMATED等',
  charged_microcredits bigint not null default 0 comment '本次审计实扣额度',
  state varchar(32) not null comment 'durable命令状态',
  retry_count int not null default 0 comment '恢复尝试次数',
  next_retry_at datetime(3) default null comment '下次恢复时间',
  provider_started_at datetime(3) default null comment 'provider准入时刻',
  lease_owner varchar(64) default null comment '恢复租约持有者',
  lease_until datetime(3) default null comment '恢复租约截止',
  last_error varchar(1000) default null comment '最近失败或人工审核原因',
  version int not null default 0 comment 'CAS版本',
  create_time datetime(3) not null default current_timestamp(3) comment '创建时间',
  update_time datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
  primary key (id),
  unique key uk_quota_user_request (user_id, billing_request_id),
  unique key uk_quota_freeze_id (freeze_id),
  key idx_quota_recovery (state, next_retry_at, lease_until),
  key idx_quota_llm_invocation (llm_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='Agent durable额度结算命令';

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'input_rate_snapshot') = 0,
  'alter table llm_invocation add column input_rate_snapshot bigint default null comment ''输入费率快照(额度/百万token)'' after total_tokens',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'output_rate_snapshot') = 0,
  'alter table llm_invocation add column output_rate_snapshot bigint default null comment ''输出费率快照(额度/百万token)'' after input_rate_snapshot',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'usage_source') = 0,
  'alter table llm_invocation add column usage_source varchar(16) default null comment ''PROVIDER或ESTIMATED'' after output_rate_snapshot',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'charged_microcredits') = 0,
  'alter table llm_invocation add column charged_microcredits bigint default 0 comment ''实扣微额度'' after usage_source',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

create table if not exists tool_invocation (
  id bigint not null auto_increment comment '主键',
  run_id bigint not null comment '所属run',
  llm_invocation_id bigint default null comment '来源LLM调用',
  tool_call_id varchar(128) default null comment '模型返回toolCallId',
  dispatch_index int default null comment '原始分发顺序',
  agent_name varchar(64) default null comment '当前agent名称',
  step_no int default null comment '当前步号',
  tool_name varchar(128) default null comment '工具名称',
  tool_provider varchar(32) default null comment '工具来源',
  input_json longtext comment '入参JSON',
  tool_result longtext comment '面向用户与历史回放的原始工具结果',
  llm_observation longtext comment '主智能体observation',
  status int default null comment '状态',
  error_msg longtext comment '错误信息',
  started_at datetime default null comment '开始时间',
  finished_at datetime default null comment '结束时间',
  duration_ms bigint default null comment '耗时(毫秒)',
  create_time datetime not null default current_timestamp comment '创建时间',
  update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
  deleted int not null default 0 comment '逻辑删除标记',
  primary key (id),
  key idx_run_dispatch (run_id, dispatch_index),
  key idx_llm_invocation_id (llm_invocation_id),
  key idx_tool_name_started (tool_name, started_at)
) engine=InnoDB default charset=utf8mb4 comment='单次工具调用账本';

create table if not exists artifact_record (
  id bigint not null auto_increment comment '主键',
  run_id bigint default null comment '所属run',
  request_id varchar(64) default null comment '请求ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  artifact_role varchar(32) default null comment 'input/output',
  visibility varchar(32) default null comment 'visible/internal',
  source_type varchar(32) default null comment '来源类型',
  source_name varchar(255) default null comment '来源名称',
  file_name varchar(255) default null comment '文件名',
  storage_key varchar(512) default null comment '稳定资源key',
  download_url varchar(1024) default null comment '下载地址',
  preview_url varchar(1024) default null comment '预览地址',
  mime_type varchar(128) default null comment 'MIME类型',
  file_size bigint default null comment '文件大小',
  file_hash varchar(128) default null comment '文件哈希',
  metadata_json longtext comment '扩展元数据',
  create_time datetime not null default current_timestamp comment '创建时间',
  update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
  deleted int not null default 0 comment '逻辑删除标记',
  primary key (id),
  key idx_run_role (run_id, artifact_role),
  key idx_tool_invocation_role (tool_invocation_id, artifact_role),
  key idx_request_toolcall (request_id, tool_call_id),
  key idx_run_toolcall (run_id, tool_call_id)
) engine=InnoDB default charset=utf8mb4 comment='输入输出文件归属账本';

create table if not exists owner_identity (
  id bigint not null auto_increment comment '主键ID',
  owner_id varchar(64) not null comment '历史访客ID（已废弃，仅存档）',
  token_digest varchar(128) not null comment '访客令牌摘要',
  status int default null comment '状态',
  first_seen_at datetime default null comment '首次出现时间',
  last_seen_at datetime default null comment '最近出现时间',
  last_ip varchar(64) default null comment '最近IP',
  last_user_agent varchar(512) default null comment '最近UserAgent',
  username varchar(128) default null comment '绑定用户名',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  deleted int not null default 0 comment '逻辑删除标记',
  primary key (id),
  unique key uk_owner_id (owner_id),
  unique key uk_token_digest (token_digest)
) engine=InnoDB default charset=utf8mb4 comment='历史匿名访客身份表（已废弃，仅存档）';

create table if not exists admin_user (
  id bigint not null auto_increment comment '主键ID',
  user_id varchar(64) not null comment '用户ID',
  username varchar(128) not null comment '用户名',
  password varchar(255) default null comment '密码',
  status int default null comment '状态',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_user_id (user_id),
  unique key uk_username (username)
) engine=InnoDB default charset=utf8mb4 comment='管理员用户表';

create table if not exists chat_model_info (
  id bigint not null auto_increment comment '主键ID',
  code varchar(128) default null comment '模型编码',
  type varchar(64) default null comment '模型类型',
  content longtext comment '模型内容',
  name varchar(255) default null comment '模型名称',
  use_prompt longtext comment '使用提示词',
  business_prompt longtext comment '业务提示词',
  yn int not null default 1 comment '逻辑删除(1:有效,0:删除)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  key idx_code (code)
) engine=InnoDB default charset=utf8mb4 comment='对话模型信息表';

create table if not exists chat_model_schema (
  id bigint not null auto_increment comment '主键ID',
  model_code varchar(128) default null comment '模型编码',
  column_id varchar(128) default null comment '字段ID',
  column_name varchar(255) default null comment '字段名称',
  column_comment varchar(512) default null comment '字段注释',
  few_shot longtext comment 'few-shot示例',
  data_type varchar(64) default null comment '数据类型',
  synonyms longtext comment '同义词',
  vector_uuid varchar(64) default null comment '向量UUID',
  default_recall int not null default 0 comment '默认召回',
  analyze_suggest int not null default 0 comment '分析建议',
  yn int not null default 1 comment '逻辑删除(1:有效,0:删除)',
  create_time datetime default current_timestamp comment '创建时间',
  update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  key idx_model_code (model_code)
) engine=InnoDB default charset=utf8mb4 comment='对话模型字段schema表';

create table if not exists tool_output_deep_search (
  id bigint not null auto_increment comment '主键ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  run_id bigint default null comment '运行ID',
  request_id varchar(128) default null comment '请求ID',
  request_source varchar(32) default null comment '请求来源',
  session_id varchar(128) default null comment '会话ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  status int default null comment '状态',
  error_msg varchar(1024) default null comment '错误信息',
  query varchar(1024) default null comment '检索问题',
  answer_summary longtext comment '回答摘要',
  stages_json longtext comment '阶段明细JSON',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='deep_search工具产出表';

create table if not exists tool_output_file_tool (
  id bigint not null auto_increment comment '主键ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  run_id bigint default null comment '运行ID',
  request_id varchar(128) default null comment '请求ID',
  request_source varchar(32) default null comment '请求来源',
  session_id varchar(128) default null comment '会话ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  status int default null comment '状态',
  error_msg varchar(1024) default null comment '错误信息',
  command varchar(255) default null comment '文件命令',
  primary_file_name varchar(512) default null comment '主文件名',
  preview_url varchar(1024) default null comment '预览地址',
  download_url varchar(1024) default null comment '下载地址',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='file_tool工具产出表';

create table if not exists tool_output_code_interpreter (
  id bigint not null auto_increment comment '主键ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  run_id bigint default null comment '运行ID',
  request_id varchar(128) default null comment '请求ID',
  request_source varchar(32) default null comment '请求来源',
  session_id varchar(128) default null comment '会话ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  status int default null comment '状态',
  error_msg varchar(1024) default null comment '错误信息',
  code_output longtext comment '代码运行输出',
  content longtext comment '内容',
  code longtext comment '代码',
  `explain` longtext comment '解释说明',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='code_interpreter工具产出表';

create table if not exists tool_output_report_tool (
  id bigint not null auto_increment comment '主键ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  run_id bigint default null comment '运行ID',
  request_id varchar(128) default null comment '请求ID',
  request_source varchar(32) default null comment '请求来源',
  session_id varchar(128) default null comment '会话ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  status int default null comment '状态',
  error_msg varchar(1024) default null comment '错误信息',
  file_type varchar(64) default null comment '文件类型',
  summary longtext comment '摘要',
  content longtext comment '报告内容',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='report_tool工具产出表';

create table if not exists tool_output_data_analysis (
  id bigint not null auto_increment comment '主键ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  run_id bigint default null comment '运行ID',
  request_id varchar(128) default null comment '请求ID',
  request_source varchar(32) default null comment '请求来源',
  session_id varchar(128) default null comment '会话ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  status int default null comment '状态',
  error_msg varchar(1024) default null comment '错误信息',
  task varchar(1024) default null comment '分析任务',
  summary longtext comment '摘要',
  content longtext comment '分析内容',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='data_analysis工具产出表';

create table if not exists tool_output_multimodal_agent (
  id bigint not null auto_increment comment '主键ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  run_id bigint default null comment '运行ID',
  request_id varchar(128) default null comment '请求ID',
  request_source varchar(32) default null comment '请求来源',
  session_id varchar(128) default null comment '会话ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  status int default null comment '状态',
  error_msg varchar(1024) default null comment '错误信息',
  summary longtext comment '摘要',
  markdown_content longtext comment 'Markdown内容',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='multimodal_agent工具产出表';

create table if not exists tool_output_image_generation (
  id bigint not null auto_increment comment '主键ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  run_id bigint default null comment '运行ID',
  request_id varchar(128) default null comment '请求ID',
  request_source varchar(32) default null comment '请求来源',
  owner_id bigint default null comment '所属用户ID',
  deleted tinyint(1) not null default 0 comment '逻辑删除',
  session_id varchar(128) default null comment '会话ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  status int default null comment '状态',
  error_msg varchar(1024) default null comment '错误信息',
  prompt longtext comment '提示词',
  mode varchar(32) default null comment '生成模式',
  summary longtext comment '摘要',
  size varchar(32) default null comment '图片尺寸',
  batch_count int default null comment '生成数量',
  source_image_count int default null comment '源图数量',
  mask_image_count int default null comment '蒙版图数量',
  used_fallback tinyint(1) default null comment '是否走回退链路',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id),
  key idx_request_source (request_source)
) engine=InnoDB default charset=utf8mb4 comment='image_generation工具产出表';

drop procedure if exists migrate_image_history_owner;
delimiter //
create procedure migrate_image_history_owner()
begin
  if not exists (
    select 1 from information_schema.columns
    where table_schema = database() and table_name = 'tool_output_image_generation' and column_name = 'owner_id'
  ) then
    alter table tool_output_image_generation add column owner_id bigint default null comment '所属用户ID';
  end if;
  if not exists (
    select 1 from information_schema.columns
    where table_schema = database() and table_name = 'tool_output_image_generation' and column_name = 'deleted'
  ) then
    alter table tool_output_image_generation add column deleted tinyint(1) not null default 0 comment '逻辑删除';
  end if;
end//
delimiter ;
call migrate_image_history_owner();
drop procedure migrate_image_history_owner;

create table if not exists tool_output_script_runner (
  id bigint not null auto_increment comment '主键ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  run_id bigint default null comment '运行ID',
  request_id varchar(128) default null comment '请求ID',
  request_source varchar(32) default null comment '请求来源',
  session_id varchar(128) default null comment '会话ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  status int default null comment '状态',
  error_msg varchar(1024) default null comment '错误信息',
  skill_name varchar(255) default null comment '技能名称',
  script_name varchar(255) default null comment '脚本名称',
  runtime varchar(64) default null comment '运行时',
  success tinyint(1) default null comment '是否成功',
  exit_code int default null comment '退出码',
  stdout longtext comment '标准输出',
  stderr longtext comment '标准错误',
  summary longtext comment '摘要',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='script_runner工具产出表';

create table if not exists tool_output_todo_write (
  id bigint not null auto_increment comment '主键ID',
  tool_invocation_id bigint default null comment '工具调用ID',
  run_id bigint default null comment '运行ID',
  request_id varchar(128) default null comment '请求ID',
  request_source varchar(32) default null comment '请求来源',
  session_id varchar(128) default null comment '会话ID',
  tool_call_id varchar(128) default null comment '工具调用标识',
  status int default null comment '状态',
  error_msg varchar(1024) default null comment '错误信息',
  command varchar(64) default null comment 'todo_write命令',
  before_todo_json longtext comment '执行前Todo快照JSON',
  after_todo_json longtext comment '执行后Todo快照JSON',
  current_step varchar(1024) default null comment '当前Todo项',
  current_step_index int default null comment '当前Todo项索引',
  auto_advanced tinyint(1) default null comment '是否自动推进',
  auto_finished tinyint(1) default null comment '是否全部完成',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='todo_write工具产出表';

create table if not exists agent_stream_event (
  sequence_no bigint not null auto_increment comment '全局递增事件序号',
  request_id varchar(128) not null comment '请求ID',
  event_type varchar(64) not null comment 'SSE event名称',
  event_json longtext not null comment 'canonical事件JSON',
  created_at datetime not null default current_timestamp comment '创建时间',
  primary key (sequence_no),
  key idx_agent_stream_event_request_sequence (request_id, sequence_no)
) engine=InnoDB default charset=utf8mb4 comment='Agent canonical SSE事件账本';

