use agent_db;

set @ddl = if(
  (select count(*) from information_schema.columns
   where table_schema = database() and table_name = 'tool_invocation'
     and column_name = 'tool_result') = 0,
  'alter table tool_invocation add column tool_result longtext comment ''面向用户与历史回放的原始工具结果'' after input_json',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
