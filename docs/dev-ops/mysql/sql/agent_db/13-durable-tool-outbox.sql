use agent_db;

-- P50: extend the existing replay-facing ledger without replacing it.
set @ddl = if(
  (select count(*) from information_schema.columns where table_schema = database()
   and table_name = 'tool_invocation' and column_name = 'operation_key') = 0,
  'alter table tool_invocation add column operation_key varchar(128) default null comment ''规范化工具操作幂等键'' after tool_provider',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if(
  (select count(*) from information_schema.columns where table_schema = database()
   and table_name = 'tool_invocation' and column_name = 'execution_mode') = 0,
  'alter table tool_invocation add column execution_mode varchar(16) default null comment ''EXECUTED/REUSED'' after operation_key',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if(
  (select count(*) from information_schema.columns where table_schema = database()
   and table_name = 'tool_invocation' and column_name = 'source_invocation_id') = 0,
  'alter table tool_invocation add column source_invocation_id bigint default null comment ''复用结果来源工具调用'' after execution_mode',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if(
  (select count(*) from information_schema.columns where table_schema = database()
   and table_name = 'tool_invocation' and column_name = 'durable_status') = 0,
  'alter table tool_invocation add column durable_status varchar(32) default null comment ''Durable Tool状态投影'' after source_invocation_id',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if(
  (select count(*) from information_schema.columns where table_schema = database()
   and table_name = 'tool_invocation' and column_name = 'durable_fencing_token') = 0,
  'alter table tool_invocation add column durable_fencing_token bigint default null comment ''Worker fencing token'' after durable_status',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if(
  (select count(*) from information_schema.columns where table_schema = database()
   and table_name = 'tool_invocation' and column_name = 'durable_lease_expires_at') = 0,
  'alter table tool_invocation add column durable_lease_expires_at datetime default null comment ''Worker租约截止时间'' after duration_ms',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if(
  (select count(*) from information_schema.statistics where table_schema = database()
   and table_name = 'tool_invocation' and index_name = 'uk_tool_invocation_run_tool_call') = 0,
  'alter table tool_invocation add unique key uk_tool_invocation_run_tool_call (run_id, tool_call_id)',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if(
  (select count(*) from information_schema.statistics where table_schema = database()
   and table_name = 'tool_invocation' and index_name = 'idx_tool_operation') = 0,
  'alter table tool_invocation add key idx_tool_operation (run_id, operation_key)',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if(
  (select count(*) from information_schema.statistics where table_schema = database()
   and table_name = 'tool_invocation' and index_name = 'idx_tool_durable_lease') = 0,
  'alter table tool_invocation add key idx_tool_durable_lease (durable_status, durable_lease_expires_at)',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

create table if not exists tool_attempt (
  id bigint not null auto_increment comment '主键',
  tool_invocation_id bigint not null comment 'tool_invocation主键',
  attempt_no int not null comment '尝试序号，从1开始',
  worker_id varchar(128) not null comment '执行Worker',
  fencing_token bigint not null comment 'Worker fencing token',
  provider_request_id varchar(255) default null comment 'Python/Provider请求ID',
  status varchar(32) not null comment 'RUNNING/SUCCEEDED/FAILED/TIMED_OUT/CANCELLED/UNKNOWN',
  error_type varchar(128) default null comment '标准错误类型',
  result_hash varchar(128) default null comment '结果hash',
  started_at datetime not null comment '开始时间',
  heartbeat_at datetime default null comment '最后心跳时间',
  finished_at datetime default null comment '结束时间',
  create_time datetime not null default current_timestamp,
  update_time datetime not null default current_timestamp on update current_timestamp,
  deleted int not null default 0,
  primary key (id),
  unique key uk_tool_attempt_no (tool_invocation_id, attempt_no),
  key idx_tool_attempt_worker_lease (worker_id, status, heartbeat_at),
  key idx_tool_attempt_fence (tool_invocation_id, fencing_token)
) engine=InnoDB default charset=utf8mb4 comment='Durable Tool尝试历史';

create table if not exists tool_outbox (
  id bigint not null auto_increment comment '主键',
  tool_invocation_id bigint not null comment 'tool_invocation主键',
  operation_key varchar(128) not null comment '规范化工具操作幂等键',
  status varchar(32) not null comment 'SCHEDULED/PUBLISHED/ACKNOWLEDGED/RETRY',
  retry_count int not null default 0 comment '投递重试次数',
  next_attempt_at datetime not null comment '下次投递时间',
  published_at datetime default null comment 'Kafka/DB poller投递时间',
  acknowledged_at datetime default null comment 'Worker已确认时间',
  create_time datetime not null default current_timestamp,
  update_time datetime not null default current_timestamp on update current_timestamp,
  deleted int not null default 0,
  primary key (id),
  unique key uk_tool_outbox_invocation (tool_invocation_id),
  key idx_tool_outbox_due (status, next_attempt_at),
  key idx_tool_outbox_operation (operation_key)
) engine=InnoDB default charset=utf8mb4 comment='Durable Tool Worker唤醒Outbox';
