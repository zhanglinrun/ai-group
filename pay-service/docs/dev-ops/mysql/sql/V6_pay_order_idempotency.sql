-- V6: durable client idempotency for pay order creation (safe to run repeatedly).
USE `s_pay_mall_ddd_market`;

SET @column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'client_request_id'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `client_request_id` varchar(64) DEFAULT NULL COMMENT ''客户端单次购买请求号，新订单必填'' AFTER `id`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'request_fingerprint'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `request_fingerprint` char(64) DEFAULT NULL COMMENT ''规范化下单载荷 SHA-256'' AFTER `client_request_id`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'create_stage'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `create_stage` varchar(32) NOT NULL DEFAULT ''PREPAY_READY'' COMMENT ''durable 下单创建阶段'' AFTER `request_fingerprint`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'create_owner_token'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `create_owner_token` varchar(64) DEFAULT NULL COMMENT ''创建续作 owner token'' AFTER `create_stage`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'create_lease_until'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `create_lease_until` datetime DEFAULT NULL COMMENT ''创建续作租约截止时间'' AFTER `create_owner_token`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Existing successful/terminal orders are replayable. Historical CREATE rows have unknown external
-- side effects and therefore fail closed for manual review instead of being blindly replayed.
UPDATE `pay_order`
SET `create_stage` = CASE WHEN `status` = 'CREATE' THEN 'MANUAL_REVIEW' ELSE 'PREPAY_READY' END,
    `create_owner_token` = NULL,
    `create_lease_until` = NULL
WHERE `client_request_id` IS NULL
   OR `create_stage` NOT IN ('LOCAL_CREATED','GROUP_LOCKED','PROVIDER_STARTED','PREPAY_READY','MANUAL_REVIEW');

SET @column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'group_activity_id'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `group_activity_id` bigint DEFAULT NULL COMMENT ''下单时拼团活动ID快照，直购为空'' AFTER `market_type`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'group_team_id'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE `pay_order` ADD COLUMN `group_team_id` varchar(64) DEFAULT NULL COMMENT ''下单时拼团队伍ID快照，开团为空'' AFTER `group_activity_id`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND INDEX_NAME = 'uq_user_client_request'
);
SET @ddl = IF(@index_exists = 0,
    'ALTER TABLE `pay_order` ADD UNIQUE KEY `uq_user_client_request` (`user_id`, `client_request_id`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
