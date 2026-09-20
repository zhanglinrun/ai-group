-- A formed team publishes its entire order-id list in one outbox row.
-- VARCHAR(256)/TEXT rejected otherwise valid completions as target_count grew.
-- Safe to re-run; existing payloads and notify_task UUIDs are unchanged.
USE `group_buy_market`;

ALTER TABLE `notify_task`
    MODIFY COLUMN `parameter_json` MEDIUMTEXT NOT NULL COMMENT '参数对象';
