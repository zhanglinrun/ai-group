-- Accelerate MysqlSaver checkpoint reloads used by Deep Research recovery.
-- The saver joins by thread_id and orders checkpoint rows by saved_at DESC, id DESC.
-- Safe to run repeatedly on MySQL 8.x.

use agent_db;

drop procedure if exists `ensure_langraph4j_checkpoint_order_index`;

delimiter //
create procedure `ensure_langraph4j_checkpoint_order_index`()
begin
  if not exists (
    select 1
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'LANGRAPH4J_CHECKPOINT'
      and index_name = 'idx_langraph4j_checkpoint_thread_saved_at_id'
  ) then
    create index `idx_langraph4j_checkpoint_thread_saved_at_id`
      on `LANGRAPH4J_CHECKPOINT` (`thread_id`, `saved_at` desc, `id` desc);
  end if;
end//
delimiter ;

call `ensure_langraph4j_checkpoint_order_index`;
drop procedure `ensure_langraph4j_checkpoint_order_index`;
