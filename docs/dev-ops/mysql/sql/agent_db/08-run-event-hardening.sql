-- P30 per-run durable event ledger. Safe to apply repeatedly on MySQL 8.x.
-- Legacy agent_stream_event is intentionally retained for historical reads only.

use agent_db;

create table if not exists run_event (
  id bigint not null auto_increment comment '主键',
  run_id bigint not null comment '所属dialogue_run',
  event_seq bigint not null comment 'run内严格递增事件序号',
  event_type varchar(64) not null comment 'SSE event名称',
  trace_id varchar(128) default null comment '关联trace标识',
  span_id varchar(128) default null comment '关联span标识',
  payload_json longtext not null comment 'canonical事件JSON',
  payload_summary varchar(1024) default null comment '不含载荷正文的事件摘要',
  payload_hash char(64) not null comment 'payload SHA-256',
  terminal_marker tinyint default null comment '终态唯一标识；NULL表示非终态',
  created_at datetime not null default current_timestamp comment '创建时间',
  primary key (id),
  unique key uk_run_event_sequence (run_id, event_seq),
  unique key uk_run_event_terminal (run_id, terminal_marker),
  key idx_run_event_created (run_id, created_at)
) engine=InnoDB default charset=utf8mb4 comment='按运行持久化的Agent SSE事件账本';
