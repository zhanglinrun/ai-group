-- Idempotent migrations for the current quota-pack model (MySQL 8.x).
-- This file runs after member-service/src/main/resources/schema.sql.
USE `member_db`;

-- product_sku: quota snapshot and optional group-buy mappings.
SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'product_sku' AND COLUMN_NAME = 'base_quota'
) = 0,
    'ALTER TABLE `product_sku` ADD COLUMN `base_quota` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'product_sku' AND COLUMN_NAME = 'group_goods_id'
) = 0,
    'ALTER TABLE `product_sku` ADD COLUMN `group_goods_id` VARCHAR(16) DEFAULT NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'product_sku' AND COLUMN_NAME = 'group_activity_id'
) = 0,
    'ALTER TABLE `product_sku` ADD COLUMN `group_activity_id` BIGINT DEFAULT NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- quota_account: separate monthly-free and purchased balances.
SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_account' AND COLUMN_NAME = 'free_quota_balance'
) = 0,
    'ALTER TABLE `quota_account` ADD COLUMN `free_quota_balance` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_account' AND COLUMN_NAME = 'paid_quota_balance'
) = 0,
    'ALTER TABLE `quota_account` ADD COLUMN `paid_quota_balance` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_account' AND COLUMN_NAME = 'last_free_grant_month'
) = 0,
    'ALTER TABLE `quota_account` ADD COLUMN `last_free_grant_month` VARCHAR(7) DEFAULT NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- benefit_grant_event: retain the product and granted-quota snapshots used by
-- completion/revocation idempotency decisions.
SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'benefit_grant_event' AND COLUMN_NAME = 'product_code'
) = 0,
    'ALTER TABLE `benefit_grant_event` ADD COLUMN `product_code` VARCHAR(64) NOT NULL DEFAULT ''''',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'benefit_grant_event' AND COLUMN_NAME = 'granted_quota'
) = 0,
    'ALTER TABLE `benefit_grant_event` ADD COLUMN `granted_quota` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- quota_freeze: balance-source snapshots, settlement progress and request
-- idempotency. NULL request_id values remain valid for historical rows.
SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_freeze' AND COLUMN_NAME = 'free_amount'
) = 0,
    'ALTER TABLE `quota_freeze` ADD COLUMN `free_amount` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_freeze' AND COLUMN_NAME = 'paid_amount'
) = 0,
    'ALTER TABLE `quota_freeze` ADD COLUMN `paid_amount` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_freeze' AND COLUMN_NAME = 'settled_amount'
) = 0,
    'ALTER TABLE `quota_freeze` ADD COLUMN `settled_amount` BIGINT NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_freeze' AND COLUMN_NAME = 'request_id'
) = 0,
    'ALTER TABLE `quota_freeze` ADD COLUMN `request_id` VARCHAR(64) DEFAULT NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_freeze' AND INDEX_NAME = 'uk_user_request'
) = 0,
    'ALTER TABLE `quota_freeze` ADD UNIQUE KEY `uk_user_request` (`user_id`, `request_id`)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Keep the three demo quota packs and their group-buy mappings deterministic.
INSERT INTO `product_sku`
    (`code`, `name`, `price`, `base_quota`, `status`, `group_goods_id`, `group_activity_id`)
VALUES
    ('QUOTA_LIGHT', '轻享额度包', 12.00, 60, 1, '9890002', 100201),
    ('QUOTA_STANDARD', '标准额度包', 60.00, 300, 1, '9890003', 100202),
    ('QUOTA_LARGE', '大额额度包', 140.00, 700, 1, '9890004', 100203)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `price` = VALUES(`price`),
    `base_quota` = VALUES(`base_quota`),
    `status` = VALUES(`status`),
    `group_goods_id` = VALUES(`group_goods_id`),
    `group_activity_id` = VALUES(`group_activity_id`);

-- The seeded admin bypasses registration, so create only its quota account.
-- Existing balances are deliberately preserved on repeated starts.
INSERT INTO `quota_account`
    (`user_id`, `free_quota_balance`, `paid_quota_balance`, `frozen_balance`, `last_free_grant_month`)
SELECT u.id, 5000000, 0, 0, DATE_FORMAT(CURRENT_DATE(), '%Y-%m')
FROM `auth_db`.`user` u
WHERE u.username = 'admin'
  AND NOT EXISTS (
      SELECT 1 FROM `quota_account` q WHERE q.user_id = u.id
  );
