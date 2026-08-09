-- 兼容迁移（幂等，安全重跑）。当前产品不使用人数阶梯价格，统一按活动
-- target 判断成团；默认 3 人成团，实际值仍可由管理端逐活动调整。
SET NAMES utf8mb4;
USE `group_buy_market`;

UPDATE `group_buy_activity` SET `target` = 3 WHERE `activity_id` IN (100201,100202,100203);
