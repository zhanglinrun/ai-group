-- V4: 为 pay_order 增加 settlement_notified 结算确认位（幂等迁移，兼容已初始化的库）。
-- 补偿任务据此区分"结算通知丢失需重试"与"正常等待成团"，消除每分钟错误刷屏与扫描窗口饥饿。
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market'
      AND TABLE_NAME = 'pay_order'
      AND COLUMN_NAME = 'settlement_notified'
);
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE s_pay_mall_ddd_market.pay_order ADD COLUMN settlement_notified tinyint(1) NOT NULL DEFAULT 0 COMMENT ''拼团结算是否已通知成功''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量 MARKET/DEAL_DONE（已成团结算完成）单视为已通知，避免补偿任务误扫历史数据
UPDATE s_pay_mall_ddd_market.pay_order
SET settlement_notified = 1
WHERE market_type = 1 AND status IN ('MARKET', 'DEAL_DONE') AND settlement_notified = 0;
