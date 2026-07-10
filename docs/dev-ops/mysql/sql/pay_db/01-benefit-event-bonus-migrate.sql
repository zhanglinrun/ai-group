-- 幂等迁移：s_pay_mall_ddd_market.benefit_event 增加 bonus_quota（阶梯拼团加赠额度，随权益事件透传给 member）。MySQL 8.x。
USE `s_pay_mall_ddd_market`;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = 's_pay_mall_ddd_market' AND TABLE_NAME = 'benefit_event' AND COLUMN_NAME = 'bonus_quota') = 0,
    'ALTER TABLE `benefit_event` ADD COLUMN `bonus_quota` INT DEFAULT NULL COMMENT ''阶梯拼团加赠额度'' AFTER `event_published`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
