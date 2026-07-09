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

-- 预扣幂等键：quota_freeze.request_id 列 + (user_id, request_id) 唯一键
SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_freeze' AND COLUMN_NAME = 'request_id') = 0,
    'ALTER TABLE `quota_freeze` ADD COLUMN `request_id` VARCHAR(64) DEFAULT NULL AFTER `status`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'quota_freeze' AND INDEX_NAME = 'uk_user_request') = 0,
    'ALTER TABLE `quota_freeze` ADD UNIQUE KEY `uk_user_request` (`user_id`, `request_id`)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 拼团映射列：product_sku.group_goods_id / group_activity_id（NULL 表示该 SKU 不支持拼团）
SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'product_sku' AND COLUMN_NAME = 'group_goods_id') = 0,
    'ALTER TABLE `product_sku` ADD COLUMN `group_goods_id` VARCHAR(16) DEFAULT NULL AFTER `status`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'member_db' AND TABLE_NAME = 'product_sku' AND COLUMN_NAME = 'group_activity_id') = 0,
    'ALTER TABLE `product_sku` ADD COLUMN `group_activity_id` BIGINT DEFAULT NULL AFTER `group_goods_id`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 运营端管理员的会员/配额账户：admin 直接种子进 auth_db.user，没有走注册链路的 initFree，
-- 补齐 member_account + quota_account，避免管理员体验功能时报「会员账户不存在」。
INSERT INTO `member_account` (`user_id`, `tier`, `status`)
SELECT u.id, 'FREE', 1 FROM `auth_db`.`user` u
WHERE u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM `member_account` m WHERE m.user_id = u.id);

INSERT INTO `quota_account` (`user_id`, `period_quota_balance`, `topup_quota_balance`, `frozen_balance`)
SELECT u.id, 20, 0, 0 FROM `auth_db`.`user` u
WHERE u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM `quota_account` q WHERE q.user_id = u.id);

INSERT INTO `product_sku` (`code`, `name`, `price`, `period_quota`, `topup_quota`, `member_days`, `tier`, `sku_type`, `status`, `group_goods_id`, `group_activity_id`) VALUES
('FREE', 'Free', 0.00, 20, 0, 0, 'FREE', 'FREE', 1, NULL, NULL),
('PRO_MONTH', 'Pro Monthly', 49.00, 500, 0, 30, 'PRO', 'MEMBER', 1, '9890002', 100201),
('PRO_YEAR', 'Pro Yearly', 399.00, 500, 0, 365, 'PRO', 'MEMBER', 1, '9890003', 100202),
('TOPUP_200', 'Top-up 600', 29.00, 0, 600, 0, 'FREE', 'TOPUP', 1, '9890004', 100203)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `sku_type` = VALUES(`sku_type`),
    `period_quota` = VALUES(`period_quota`),
    `topup_quota` = VALUES(`topup_quota`),
    `member_days` = VALUES(`member_days`),
    `tier` = VALUES(`tier`),
    `group_goods_id` = VALUES(`group_goods_id`),
    `group_activity_id` = VALUES(`group_activity_id`);
