-- P30 durable worker lease, fencing and explicit cancellation.
-- Safe to execute repeatedly on MySQL 8.x. It never takes over legacy work.

use agent_db;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'owner_worker_id') = 0,
  'alter table dialogue_run add column owner_worker_id varchar(128) default null comment ''当前持有运行租约的worker'' after heartbeat_at',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'lease_expires_at') = 0,
  'alter table dialogue_run add column lease_expires_at datetime default null comment ''worker租约失效时间'' after owner_worker_id',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'fencing_token') = 0,
  'alter table dialogue_run add column fencing_token bigint not null default 1 comment ''运行租约fencing token'' after lease_expires_at',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'version') = 0,
  'alter table dialogue_run add column version bigint not null default 1 comment ''运行状态CAS版本'' after fencing_token',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'cancel_requested_at') = 0,
  'alter table dialogue_run add column cancel_requested_at datetime default null comment ''用户取消请求时间'' after version',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'cancel_requested_by') = 0,
  'alter table dialogue_run add column cancel_requested_by varchar(64) default null comment ''取消请求用户ID'' after cancel_requested_at',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'dialogue_run'
                 and column_name = 'terminal_at') = 0,
  'alter table dialogue_run add column terminal_at datetime default null comment ''首个终态写入时间'' after cancel_requested_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
               where table_schema = database() and table_name = 'dialogue_run'
                 and index_name = 'idx_run_lease') = 0,
  'alter table dialogue_run add key idx_run_lease (status, lease_expires_at)',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
               where table_schema = database() and table_name = 'dialogue_run'
                 and index_name = 'idx_run_owner_cancel') = 0,
  'alter table dialogue_run add key idx_run_owner_cancel (owner_id, cancel_requested_at)',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- Historical RUNNING rows are intentionally not assigned to a live worker.
-- They remain recovery-only and are terminalized by the existing reaper after
-- their deadline rather than being silently taken over by a new process.
update dialogue_run
set fencing_token = coalesce(fencing_token, 1),
    version = coalesce(version, 1),
    lease_expires_at = coalesce(lease_expires_at, deadline_at)
where deleted = 0
  and status = 0;
