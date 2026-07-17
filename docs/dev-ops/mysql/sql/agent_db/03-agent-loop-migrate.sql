-- Add unified Agent Loop structured output storage.
-- Safe to run repeatedly on MySQL 8.x.
-- Retired checkpoint/draw/planning tables are intentionally not dropped here: this file
-- runs on every local startup, while destructive retirement requires an explicit backup
-- and a maintenance window after all legacy instances have been drained.

use agent_db;

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
  current_step varchar(1024) default null comment '当前Todo',
  current_step_index int default null comment '当前Todo索引',
  auto_advanced tinyint(1) default null comment '是否自动推进',
  auto_finished tinyint(1) default null comment '是否全部完成',
  created_at datetime default current_timestamp comment '创建时间',
  updated_at datetime default current_timestamp on update current_timestamp comment '更新时间',
  primary key (id),
  unique key uk_request_tool_call (request_id, tool_call_id),
  key idx_tool_invocation_id (tool_invocation_id)
) engine=InnoDB default charset=utf8mb4 comment='todo_write工具产出表';
