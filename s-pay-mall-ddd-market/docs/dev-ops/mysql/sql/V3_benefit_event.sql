-- Phase 3: benefit event tracking for group-buy completed grants (idempotent)
USE `s_pay_mall_ddd_market`;

SET @col_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'product_code'
);
SET @ddl = IF(
    @col_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `product_code` varchar(64) DEFAULT NULL COMMENT ''member SKU code'' AFTER `product_id`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @base_quota_col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'base_quota_snapshot'
);
SET @ddl = IF(
    @base_quota_col_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `base_quota_snapshot` bigint NOT NULL DEFAULT 0 COMMENT ''base quota captured at order creation'' AFTER `product_name`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `benefit_event` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `event_id` varchar(64) NOT NULL COMMENT '事件ID',
    `event_type` varchar(64) NOT NULL COMMENT '事件类型',
    `user_id` bigint NOT NULL COMMENT '用户ID',
    `order_id` varchar(32) NOT NULL COMMENT '订单ID',
    `product_code` varchar(64) NOT NULL COMMENT 'SKU编码',
    `event_published` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'MQ是否已发布',
    `base_quota` bigint NOT NULL DEFAULT '0' COMMENT '下单时基础额度快照（整额度点）',
    `bonus_quota` bigint NOT NULL DEFAULT '0' COMMENT '成团档位加赠额度（整额度点）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    UNIQUE KEY `uk_order_event_type` (`order_id`, `event_type`),
    KEY `idx_event_published` (`event_type`, `event_published`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权益事件发布追踪';

SET @benefit_base_col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'benefit_event'
      AND COLUMN_NAME = 'base_quota'
);
SET @ddl = IF(
    @benefit_base_col_exists = 0,
    'ALTER TABLE `benefit_event` ADD COLUMN `base_quota` bigint NOT NULL DEFAULT 0 COMMENT ''base quota snapshot'' AFTER `event_published`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @benefit_bonus_col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'benefit_event'
      AND COLUMN_NAME = 'bonus_quota'
);
SET @ddl = IF(
    @benefit_bonus_col_exists = 0,
    'ALTER TABLE `benefit_event` ADD COLUMN `bonus_quota` bigint NOT NULL DEFAULT 0 COMMENT ''group tier bonus quota'' AFTER `base_quota`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
