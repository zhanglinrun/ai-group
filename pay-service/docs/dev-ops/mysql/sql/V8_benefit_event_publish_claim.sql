-- Claimable benefit outbox state for multi-instance publishers.
USE `s_pay_mall_ddd_market`;

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'benefit_event'
      AND COLUMN_NAME = 'publish_status'
);
SET @ddl = IF(
    @col_exists = 0,
    'ALTER TABLE `benefit_event` ADD COLUMN `publish_status` varchar(16) NOT NULL DEFAULT ''PENDING'' COMMENT ''outbox publisher claim state'' AFTER `event_published`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `benefit_event`
SET `publish_status` = IF(`event_published` = 1, 'SENT', 'PENDING')
WHERE `publish_status` IS NULL OR `publish_status` = '';

SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'benefit_event'
      AND INDEX_NAME = 'idx_publish_status_scan'
);
SET @ddl = IF(
    @idx_exists = 0,
    'ALTER TABLE `benefit_event` ADD INDEX `idx_publish_status_scan` (`event_published`,`publish_status`,`id`)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
