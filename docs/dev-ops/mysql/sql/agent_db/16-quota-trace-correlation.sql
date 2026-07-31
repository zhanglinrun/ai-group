-- P130: make quota recovery replay the same request/trace pair recorded at admission.
-- Safe to run repeatedly on MySQL 8.x.

use agent_db;

set @ddl = if((select count(*) from information_schema.columns
               where table_schema = database() and table_name = 'quota_settlement_command'
                 and column_name = 'trace_id') = 0,
  'alter table quota_settlement_command add column trace_id varchar(64) default null comment ''来源Run的分布式Trace标识'' after billing_request_id',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
