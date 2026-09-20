-- Apply before deploying Pay code that reads group_source/group_channel.
-- Legacy rows remain NULL: never infer a channel from the current application config.
USE `s_pay_mall_ddd_market`;

SET @column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market' AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'group_source'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `group_source` varchar(8) DEFAULT NULL COMMENT ''group lock source snapshot'' AFTER `group_team_id`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market' AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'group_channel'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `group_channel` varchar(8) DEFAULT NULL COMMENT ''group lock channel snapshot'' AFTER `group_source`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
