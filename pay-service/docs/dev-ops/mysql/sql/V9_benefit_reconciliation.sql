-- Apply after V8_benefit_event_publish_claim.sql. Requires the SENT publisher state.
USE `s_pay_mall_ddd_market`;

SET @outbox_order_length = (
    SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'benefit_event' AND COLUMN_NAME = 'order_id'
);
SET @ddl = IF(@outbox_order_length < 64,
    'ALTER TABLE `benefit_event` MODIFY COLUMN `order_id` varchar(64) NOT NULL COMMENT ''order ID''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V8's ADD COLUMN default PENDING may leave older published rows without SENT.
UPDATE `benefit_event`
SET `publish_status` = 'SENT'
WHERE `event_published` = 1 AND `publish_status` <> 'SENT';

CREATE TABLE IF NOT EXISTS `benefit_reconciliation` (
    `event_id` varchar(64) NOT NULL,
    `outcome` varchar(16) NOT NULL DEFAULT 'READY',
    `replay_attempts` int unsigned NOT NULL DEFAULT 0,
    `check_count` int unsigned NOT NULL DEFAULT 0,
    `order_status` varchar(32) DEFAULT NULL,
    `member_status` varchar(32) DEFAULT NULL,
    `detail` varchar(64) DEFAULT NULL,
    `next_check_at` datetime DEFAULT NULL,
    `claim_token` varchar(64) DEFAULT NULL,
    `lease_until` datetime DEFAULT NULL,
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`event_id`),
    KEY `idx_reconcile_due` (`outcome`, `next_check_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Pay published benefit consumer outcome checks';

-- Supports the capped aged-SENT scan (no cursor that can skip older rows).
SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'benefit_event' AND INDEX_NAME = 'idx_benefit_reconcile_scan'
);
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE `benefit_event` ADD INDEX `idx_benefit_reconcile_scan` (`event_type`, `event_published`, `publish_status`, `update_time`, `id`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
