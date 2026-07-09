CREATE DATABASE IF NOT EXISTS `member_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `member_db`;

CREATE TABLE IF NOT EXISTS `product_sku` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(64) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `price` DECIMAL(10,2) NOT NULL DEFAULT 0,
    `period_quota` INT NOT NULL DEFAULT 0,
    `topup_quota` INT NOT NULL DEFAULT 0,
    `member_days` INT NOT NULL DEFAULT 0,
    `tier` VARCHAR(32) NOT NULL,
    `sku_type` VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    `status` TINYINT NOT NULL DEFAULT 1,
    -- 拼团映射：该 SKU 在 group_buy_market 中对应的商品/活动（NULL 表示不支持拼团）
    `group_goods_id` VARCHAR(16) DEFAULT NULL,
    `group_activity_id` BIGINT DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `member_account` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `tier` VARCHAR(32) NOT NULL DEFAULT 'FREE',
    `start_at` DATETIME DEFAULT NULL,
    `expire_at` DATETIME DEFAULT NULL,
    `last_period_grant_month` VARCHAR(7) DEFAULT NULL,
    `status` TINYINT NOT NULL DEFAULT 1,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `quota_account` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `period_quota_balance` INT NOT NULL DEFAULT 0,
    `topup_quota_balance` INT NOT NULL DEFAULT 0,
    `frozen_balance` INT NOT NULL DEFAULT 0,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `quota_ledger` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `type` VARCHAR(32) NOT NULL,
    `amount` INT NOT NULL,
    `freeze_id` VARCHAR(64) DEFAULT NULL,
    `ability_code` VARCHAR(64) DEFAULT NULL,
    `remark` VARCHAR(255) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_freeze_id` (`freeze_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `benefit_grant_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `idempotency_key` VARCHAR(128) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `order_id` VARCHAR(64) NOT NULL,
    `event_type` VARCHAR(64) NOT NULL,
    `product_code` VARCHAR(64) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `member_days_delta` INT NOT NULL DEFAULT 0,
    `period_quota_granted` INT NOT NULL DEFAULT 0,
    `topup_quota_granted` INT NOT NULL DEFAULT 0,
    `tier_effect` VARCHAR(32) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotency` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `quota_freeze` (
    `freeze_id` VARCHAR(64) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `amount` INT NOT NULL,
    `ability_code` VARCHAR(64) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `request_id` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`freeze_id`),
    -- 预扣幂等键：同一用户同一 requestId 至多一条冻结记录（request_id 为 NULL 时允许多条，兼容历史无键调用）
    UNIQUE KEY `uk_user_request` (`user_id`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `product_sku` (`code`, `name`, `price`, `period_quota`, `topup_quota`, `member_days`, `tier`, `sku_type`, `status`, `group_goods_id`, `group_activity_id`) VALUES
('FREE', 'Free', 0.00, 20, 0, 0, 'FREE', 'FREE', 1, NULL, NULL),
('PRO_MONTH', 'Pro Monthly', 49.00, 500, 0, 30, 'PRO', 'MEMBER', 1, '9890002', 100201),
('PRO_YEAR', 'Pro Yearly', 399.00, 500, 0, 365, 'PRO', 'MEMBER', 1, '9890003', 100202),
('TOPUP_200', 'Top-up 600', 29.00, 0, 600, 0, 'FREE', 'TOPUP', 1, '9890004', 100203)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `sku_type` = VALUES(`sku_type`),
    `topup_quota` = VALUES(`topup_quota`),
    `group_goods_id` = VALUES(`group_goods_id`),
    `group_activity_id` = VALUES(`group_activity_id`);
