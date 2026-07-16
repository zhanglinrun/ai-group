-- Additive migration for existing agent_db installations.
-- Safe to run repeatedly; no existing execution-ledger row is changed.

create database if not exists agent_db
  default character set utf8mb4
  default collate utf8mb4_general_ci;

use agent_db;

set names utf8mb4;

create table if not exists agent_run_checkpoint (
  id bigint not null auto_increment comment '主键',
  checkpoint_id varchar(64) not null comment '对外不可猜测的恢复点ID',
  run_id bigint not null comment '来源run',
  request_id varchar(64) not null comment '来源请求ID',
  session_id varchar(64) not null comment '会话ID',
  owner_id varchar(64) not null comment '所有权边界',
  sequence_no int not null comment 'run内递增序号',
  phase varchar(32) not null comment 'READY_FOR_STEP/BEFORE_SUMMARY',
  step_index int default null comment '恢复后的下一步骤索引',
  snapshot_json longtext not null comment '受限且脱敏的最小状态快照',
  snapshot_hash char(64) not null comment '快照SHA-256',
  resumable tinyint(1) not null default 1 comment '是否允许恢复',
  resumed_by_request_id varchar(64) default null comment '消费该恢复点的新请求',
  resume_decision varchar(32) default null comment 'SAFE_ONLY/RESTART_FROM_CHECKPOINT',
  resumed_at datetime default null comment '恢复认领时间',
  created_at datetime not null default current_timestamp comment '创建时间',
  updated_at datetime not null default current_timestamp on update current_timestamp comment '更新时间',
  deleted tinyint(1) not null default 0 comment '逻辑删除',
  primary key (id),
  unique key uk_checkpoint_id (checkpoint_id),
  unique key uk_run_sequence (run_id, sequence_no),
  key idx_owner_session_created (owner_id, session_id, created_at),
  key idx_resume_request (resumed_by_request_id)
) engine=InnoDB default charset=utf8mb4 comment='Plan-Solve安全恢复点';
