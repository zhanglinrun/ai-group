-- Drop unused prototype columns/tables. Safe to re-run.
SET NAMES utf8mb4;
USE `group_buy_market`;

DROP TABLE IF EXISTS `group_buy_activity_tier`;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = 'group_buy_market'
                 AND TABLE_NAME = 'group_buy_activity'
                 AND COLUMN_NAME = 'activity_type') > 0,
    'ALTER TABLE `group_buy_activity` DROP COLUMN `activity_type`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = 'group_buy_market'
                 AND TABLE_NAME = 'group_buy_order'
                 AND COLUMN_NAME = 'tier_snapshot') > 0,
    'ALTER TABLE `group_buy_order` DROP COLUMN `tier_snapshot`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
