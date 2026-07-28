-- Agent-owned durable reserve/confirm/release command log.
-- Safe to run repeatedly on MySQL 8.x.

use agent_db;

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

set @ddl = if((select character_maximum_length from information_schema.columns
               where table_schema = database() and table_name = 'quota_settlement_command'
                 and column_name = 'usage_source') < 32,
  'alter table quota_settlement_command modify column usage_source varchar(32) default null comment ''PROVIDER/MIXED/ESTIMATED等''',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
