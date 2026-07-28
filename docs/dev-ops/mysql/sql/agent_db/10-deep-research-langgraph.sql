-- LangGraph4j checkpoint tables for 熊博士 deep research.
-- Runtime uses CreateOption.CREATE_NONE, so schema changes stay explicit.

create table if not exists `LANGRAPH4J_THREAD` (
  `thread_id` varchar(36) not null,
  `thread_name` varchar(255),
  `is_released` boolean not null default false,
  primary key (`thread_id`),
  unique key `IDX_LANGRAPH4J_THREAD_NAME_RELEASED` (`thread_name`, `is_released`)
) engine=InnoDB default charset=utf8mb4;

create table if not exists `LANGRAPH4J_CHECKPOINT` (
  `id` bigint unsigned not null auto_increment unique key,
  `checkpoint_id` varchar(36) not null,
  `thread_id` varchar(36) not null,
  `node_id` varchar(255),
  `next_node_id` varchar(255),
  `state_data` json not null,
  `saved_at` timestamp(6) default current_timestamp(6),
  primary key (`checkpoint_id`),
  key `idx_langraph4j_checkpoint_thread` (`thread_id`),
  constraint `LANGRAPH4J_FK_THREAD`
    foreign key (`thread_id`) references `LANGRAPH4J_THREAD` (`thread_id`)
    on delete cascade
) engine=InnoDB default charset=utf8mb4;

drop procedure if exists `ensure_langraph4j_column`;

delimiter //
create procedure `ensure_langraph4j_column`(
  in in_table_name varchar(64),
  in in_column_name varchar(64),
  in in_column_ddl text
)
begin
  if not exists (
    select 1
    from information_schema.columns
    where table_schema = database()
      and table_name = in_table_name
      and column_name = in_column_name
  ) then
    set @ddl = concat('alter table `', in_table_name, '` add column ', in_column_ddl);
    prepare stmt from @ddl;
    execute stmt;
    deallocate prepare stmt;
  end if;
end//
delimiter ;

call `ensure_langraph4j_column`('LANGRAPH4J_CHECKPOINT', 'id',
  '`id` bigint unsigned not null auto_increment unique key first');
call `ensure_langraph4j_column`('LANGRAPH4J_CHECKPOINT', 'node_id',
  '`node_id` varchar(255) after `thread_id`');
call `ensure_langraph4j_column`('LANGRAPH4J_CHECKPOINT', 'next_node_id',
  '`next_node_id` varchar(255) after `node_id`');
call `ensure_langraph4j_column`('LANGRAPH4J_CHECKPOINT', 'state_data',
  '`state_data` json not null after `next_node_id`');
call `ensure_langraph4j_column`('LANGRAPH4J_CHECKPOINT', 'saved_at',
  '`saved_at` timestamp(6) default current_timestamp(6) after `state_data`');

drop procedure `ensure_langraph4j_column`;

drop procedure if exists `relax_legacy_langraph4j_column`;

delimiter //
create procedure `relax_legacy_langraph4j_column`(
  in in_table_name varchar(64),
  in in_column_name varchar(64),
  in in_column_ddl text
)
begin
  if exists (
    select 1
    from information_schema.columns
    where table_schema = database()
      and table_name = in_table_name
      and column_name = in_column_name
  ) then
    set @ddl = concat('alter table `', in_table_name, '` modify column ', in_column_ddl);
    prepare stmt from @ddl;
    execute stmt;
    deallocate prepare stmt;
  end if;
end//
delimiter ;

call `relax_legacy_langraph4j_column`('LANGRAPH4J_CHECKPOINT', 'checkpoint', '`checkpoint` json null');
call `relax_legacy_langraph4j_column`('LANGRAPH4J_CHECKPOINT', 'metadata', '`metadata` json null');

drop procedure `relax_legacy_langraph4j_column`;
