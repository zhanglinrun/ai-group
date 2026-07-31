-- M1: replayable, secret-free snapshot fields for every Agent model invocation.
-- Safe to run repeatedly on MySQL 8.x. 01-agent_db.sql contains the same
-- columns for fresh installations, so each ALTER is guarded independently.
use agent_db;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'cost_owner') = 0,
  'alter table llm_invocation add column cost_owner varchar(32) not null default ''USER_QUOTA'' comment ''USER_QUOTA or PLATFORM_COST'' after model_name',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'prompt_hash') = 0,
  'alter table llm_invocation add column prompt_hash varchar(64) default null comment ''SHA-256 of prompt payload'' after cost_owner',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'model_parameters_json') = 0,
  'alter table llm_invocation add column model_parameters_json text comment ''secret-free effective model parameters'' after prompt_hash',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'tool_snapshot_json') = 0,
  'alter table llm_invocation add column tool_snapshot_json longtext comment ''active tool schema snapshot'' after model_parameters_json',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'skill_snapshot_json') = 0,
  'alter table llm_invocation add column skill_snapshot_json longtext comment ''active skill snapshot'' after tool_snapshot_json',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'config_hash') = 0,
  'alter table llm_invocation add column config_hash varchar(64) default null comment ''SHA-256 of config and capability snapshot'' after skill_snapshot_json',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'llm_invocation'
                 and column_name = 'provider_latency_ms') = 0,
  'alter table llm_invocation add column provider_latency_ms bigint default null comment ''provider call latency in milliseconds'' after duration_ms',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
