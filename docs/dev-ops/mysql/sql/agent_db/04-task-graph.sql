-- ChatGPT Work 控制面数据模型，由 TaskGraphService 的 JDBC repository 使用。
-- 领域测试仍可使用内存 repository；执行本迁移不会改变 Agent Loop 运行表。
use agent_db;

create table if not exists agent_workspace (
  id bigint not null auto_increment,
  workspace_uid varchar(64) not null,
  owner_id varchar(64) not null,
  name varchar(255) not null,
  instructions text,
  tool_policy_json json,
  created_at datetime not null default current_timestamp,
  updated_at datetime not null default current_timestamp on update current_timestamp,
  primary key (id),
  unique key uk_workspace_uid (workspace_uid),
  key idx_workspace_owner (owner_id, updated_at)
) engine=InnoDB default charset=utf8mb4 comment='Agent Work 项目工作区';

create table if not exists agent_task_node (
  id bigint not null auto_increment,
  task_uid varchar(64) not null,
  workspace_uid varchar(64) not null,
  owner_id varchar(64) not null,
  subject varchar(255) not null,
  description text,
  active_form varchar(255),
  status varchar(32) not null default 'PENDING',
  assignee varchar(128),
  metadata_json json,
  version int not null default 0,
  created_at datetime not null default current_timestamp,
  updated_at datetime not null default current_timestamp on update current_timestamp,
  completed_at datetime null,
  primary key (id),
  unique key uk_task_uid (task_uid),
  key idx_task_workspace (workspace_uid, status, updated_at)
) engine=InnoDB default charset=utf8mb4 comment='Agent Work 任务节点';

create table if not exists agent_task_dependency (
  blocker_task_uid varchar(64) not null,
  blocked_task_uid varchar(64) not null,
  created_at datetime not null default current_timestamp,
  primary key (blocker_task_uid, blocked_task_uid),
  key idx_dependency_blocked (blocked_task_uid)
) engine=InnoDB default charset=utf8mb4 comment='Agent Work 任务依赖边';

create table if not exists agent_task_event (
  id bigint not null auto_increment,
  event_uid varchar(64) not null,
  workspace_uid varchar(64) not null,
  task_uid varchar(64) null,
  event_type varchar(64) not null,
  actor_id varchar(64) not null,
  payload_json json,
  created_at datetime not null default current_timestamp,
  primary key (id),
  unique key uk_task_event_uid (event_uid),
  key idx_task_event_cursor (workspace_uid, id)
) engine=InnoDB default charset=utf8mb4 comment='Agent Work 任务事件审计';
