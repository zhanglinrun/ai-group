-- Idempotent member_db migrations (MySQL 8.x compatible).
USE `member_db`;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'product_sku' AND COLUMN_NAME = 'sku_type') = 0,
    'ALTER TABLE `product_sku` ADD COLUMN `sku_type` VARCHAR(32) NOT NULL DEFAULT ''MEMBER'' AFTER `tier`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'member_account' AND COLUMN_NAME = 'last_period_grant_month') = 0,
    'ALTER TABLE `member_account` ADD COLUMN `last_period_grant_month` VARCHAR(7) DEFAULT NULL AFTER `expire_at`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'benefit_grant_event' AND COLUMN_NAME = 'member_days_delta') = 0,
    'ALTER TABLE `benefit_grant_event` ADD COLUMN `member_days_delta` INT NOT NULL DEFAULT 0 AFTER `status`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'benefit_grant_event' AND COLUMN_NAME = 'period_quota_granted') = 0,
    'ALTER TABLE `benefit_grant_event` ADD COLUMN `period_quota_granted` INT NOT NULL DEFAULT 0 AFTER `member_days_delta`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'benefit_grant_event' AND COLUMN_NAME = 'topup_quota_granted') = 0,
    'ALTER TABLE `benefit_grant_event` ADD COLUMN `topup_quota_granted` INT NOT NULL DEFAULT 0 AFTER `period_quota_granted`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'benefit_grant_event' AND COLUMN_NAME = 'tier_effect') = 0,
    'ALTER TABLE `benefit_grant_event` ADD COLUMN `tier_effect` VARCHAR(32) DEFAULT NULL AFTER `topup_quota_granted`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO `product_sku` (`code`, `name`, `price`, `period_quota`, `topup_quota`, `member_days`, `tier`, `sku_type`, `status`) VALUES
('FREE', 'Free', 0.00, 20, 0, 0, 'FREE', 'FREE', 1),
('PRO_MONTH', 'Pro Monthly', 49.00, 500, 0, 30, 'PRO', 'MEMBER', 1),
('PRO_YEAR', 'Pro Yearly', 399.00, 500, 0, 365, 'PRO', 'MEMBER', 1),
('TOPUP_200', 'Top-up 200', 29.00, 0, 200, 0, 'FREE', 'TOPUP', 1)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `sku_type` = VALUES(`sku_type`),
    `period_quota` = VALUES(`period_quota`),
    `topup_quota` = VALUES(`topup_quota`),
    `member_days` = VALUES(`member_days`),
    `tier` = VALUES(`tier`);
