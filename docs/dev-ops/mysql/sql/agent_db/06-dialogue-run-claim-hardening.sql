-- Harden durable request-id claims with payload identity and worker-lost terminalization.
-- Safe to run repeatedly on MySQL 8.x. This migration never replays or takes over a run.

use agent_db;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'request_fingerprint') = 0,
  'alter table dialogue_run add column request_fingerprint varchar(64) default null comment ''客户端请求稳定指纹，旧历史行允许为空'' after query_text',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'deadline_at') = 0,
  'alter table dialogue_run add column deadline_at datetime default null comment ''运行绝对截止时间'' after started_at',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'heartbeat_at') = 0,
  'alter table dialogue_run add column heartbeat_at datetime default null comment ''执行进程最后心跳时间'' after deadline_at',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
               where table_schema = database() and table_name = 'dialogue_run'
                 and index_name = 'idx_run_recovery') = 0,
  'alter table dialogue_run add key idx_run_recovery (status, deadline_at, heartbeat_at)',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- Existing RUNNING rows predate persisted deadlines. Give them the historical
-- 15-minute default plus the configured recovery grace before the reaper can
-- terminalize them. Terminal history intentionally keeps fingerprint = NULL.
update dialogue_run
set deadline_at = coalesce(deadline_at, date_add(coalesce(started_at, create_time), interval 15 minute)),
    heartbeat_at = coalesce(heartbeat_at, update_time, started_at, create_time)
where deleted = 0
  and status = 0
  and (deadline_at is null or heartbeat_at is null);
