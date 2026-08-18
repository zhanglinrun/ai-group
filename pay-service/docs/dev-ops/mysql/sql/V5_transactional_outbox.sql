-- Unify order-fulfillment and entitlement messages in the existing benefit_event outbox.
-- Idempotent for existing development/production databases (MySQL 8.x).
USE `s_pay_mall_ddd_market`;

SET @outbox_idx_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'benefit_event'
      AND INDEX_NAME = 'idx_outbox_publish_scan'
);
SET @ddl = IF(
    @outbox_idx_exists = 0,
    'ALTER TABLE `benefit_event` ADD INDEX `idx_outbox_publish_scan` (`event_published`, `id`)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @outbox_comment_needs_update = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'benefit_event'
      AND TABLE_COMMENT <> '支付交易本地消息表（履约/权益 outbox）'
);
SET @ddl = IF(
    @outbox_comment_needs_update > 0,
    'ALTER TABLE `benefit_event` COMMENT = ''支付交易本地消息表（履约/权益 outbox）''',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Drop leftover unused column from earlier prototypes. Safe if already absent.
SET @bonus_quota_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'benefit_event'
      AND COLUMN_NAME = 'bonus_quota'
);
SET @ddl = IF(
    @bonus_quota_exists > 0,
    'ALTER TABLE `benefit_event` DROP COLUMN `bonus_quota`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Remove rows produced by an early version of this migration: a group order in
-- PAY_SUCCESS is only waiting for the team and must not be fulfilled yet.
DELETE event_row
FROM `benefit_event` event_row
JOIN `pay_order` pay_order_row ON pay_order_row.order_id = event_row.order_id
WHERE event_row.event_type = 'ORDER_PAY_SUCCESS'
  AND event_row.event_published = 0
  AND pay_order_row.market_type = 1
  AND pay_order_row.status = 'PAY_SUCCESS';

-- Heal legacy fulfillment messages only after the business is eligible:
-- direct purchase paid, or group purchase already settled to MARKET.
-- The unique order+event_type key makes repeated execution safe.
INSERT IGNORE INTO `benefit_event`(
    event_id, event_type, user_id, order_id, product_code,
    event_published, base_quota, create_time, update_time)
SELECT UUID(), 'ORDER_PAY_SUCCESS', CAST(user_id AS UNSIGNED), order_id,
       COALESCE(NULLIF(product_code, ''), NULLIF(product_id, ''), 'UNKNOWN'),
       0, COALESCE(base_quota_snapshot, 0), NOW(), NOW()
FROM `pay_order`
WHERE user_id REGEXP '^[0-9]+$'
  AND ((market_type = 0 AND status = 'PAY_SUCCESS')
    OR (market_type = 1 AND status = 'MARKET'));

-- The old direct-purchase path could commit PAY_SUCCESS, fail its immediate
-- fulfillment send, and never reach the subsequent quota event creation.
INSERT IGNORE INTO `benefit_event`(
    event_id, event_type, user_id, order_id, product_code,
    event_published, base_quota, create_time, update_time)
SELECT UUID(), 'GROUP_BUY_COMPLETED', CAST(user_id AS UNSIGNED), order_id,
       COALESCE(NULLIF(product_code, ''), NULLIF(product_id, ''), 'UNKNOWN'),
       0, COALESCE(base_quota_snapshot, 0), NOW(), NOW()
FROM `pay_order`
WHERE user_id REGEXP '^[0-9]+$'
  AND market_type = 0
  AND status IN ('PAY_SUCCESS', 'DEAL_DONE');
