-- Persist the fixed-role snapshot independently from entry_agent execution mode.
-- Safe to run repeatedly on MySQL 8.x.

use agent_db;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'role_agent_id') = 0,
  'alter table dialogue_run add column role_agent_id varchar(64) default null comment ''本轮固定角色ID快照'' after entry_agent',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'role_agent_name') = 0,
  'alter table dialogue_run add column role_agent_name varchar(128) default null comment ''本轮固定角色名称快照'' after role_agent_id',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
