-- The MySQL entrypoint executes this file with the client default charset. Set
-- it explicitly so Chinese SKU names are stored as UTF-8 instead of the
-- classic latin1 mojibake ("轻" becoming "è½").
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS `member_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `member_db`;


CREATE TABLE IF NOT EXISTS `product_sku` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(64) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `price` DECIMAL(10,2) NOT NULL,
    `base_quota` BIGINT NOT NULL,
    `status` TINYINT NOT NULL DEFAULT 1,
    `group_goods_id` VARCHAR(16) DEFAULT NULL,
    `group_activity_id` BIGINT DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `quota_account` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `free_quota_balance` BIGINT NOT NULL DEFAULT 0,
    `paid_quota_balance` BIGINT NOT NULL DEFAULT 0,
    `frozen_balance` BIGINT NOT NULL DEFAULT 0,
    `last_free_grant_month` VARCHAR(7) DEFAULT NULL,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `quota_ledger` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `type` VARCHAR(32) NOT NULL,
    `amount` BIGINT NOT NULL,
    `freeze_id` VARCHAR(64) DEFAULT NULL,
    `ability_code` VARCHAR(64) DEFAULT NULL,
    `trace_id` VARCHAR(64) DEFAULT NULL,
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
    `granted_quota` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotency` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `quota_freeze` (
    `freeze_id` VARCHAR(64) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `amount` BIGINT NOT NULL,
    `free_amount` BIGINT NOT NULL,
    `paid_amount` BIGINT NOT NULL,
    `settled_amount` BIGINT NOT NULL DEFAULT 0,
    `requested_amount` BIGINT DEFAULT NULL,
    `min_amount` BIGINT DEFAULT NULL,
    `ability_code` VARCHAR(64) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `request_id` VARCHAR(64) DEFAULT NULL,
    `trace_id` VARCHAR(64) DEFAULT NULL,
    `request_fingerprint` VARCHAR(64) DEFAULT NULL,
    `owner_service` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`freeze_id`),
    UNIQUE KEY `uk_user_request` (`user_id`, `request_id`),
    KEY `idx_managed_expiry` (`owner_service`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `product_sku`
    (`code`, `name`, `price`, `base_quota`, `status`, `group_goods_id`, `group_activity_id`)
VALUES
    ('QUOTA_LIGHT', '轻享额度包', 12.00, 60, 1, '9890002', 100201),
    ('QUOTA_STANDARD', '标准额度包', 60.00, 300, 1, '9890003', 100202),
    ('QUOTA_LARGE', '大额额度包', 140.00, 700, 1, '9890004', 100203);
