-- Idempotent per-SKU group-buy migration (safe to re-run).
-- 为每个会员/额度 SKU 建立独立拼团链：goods(sku) + discount + activity + 渠道映射，
-- 并把 legacy 活动 100123 的时长/参与上限调到可演示水平。
USE `group_buy_market`;

-- 1) 商品（goods）
INSERT INTO `sku` (`source`, `channel`, `goods_id`, `goods_name`, `original_price`)
VALUES
  ('s01','c01','9890002','Pro 月卡',49.00),
  ('s01','c01','9890003','Pro 年卡',399.00),
  ('s01','c01','9890004','额度加油包',29.00)
ON DUPLICATE KEY UPDATE `goods_name` = VALUES(`goods_name`), `original_price` = VALUES(`original_price`);

-- 2) 折扣（ZJ 直减）：月卡49-10=39、年卡399-70=329、加油包29-10=19
INSERT INTO `group_buy_discount` (`discount_id`, `discount_name`, `discount_desc`, `discount_type`, `market_plan`, `market_expr`, `tag_id`)
VALUES
  ('25120301','月卡拼团立减','Pro月卡拼团直减10元：49-10=39',0,'ZJ','10',NULL),
  ('25120302','年卡拼团立减','Pro年卡拼团直减70元：399-70=329',0,'ZJ','70',NULL),
  ('25120303','加油包拼团立减','额度加油包拼团直减10元：29-10=19',0,'ZJ','10',NULL)
ON DUPLICATE KEY UPDATE `discount_name` = VALUES(`discount_name`), `discount_desc` = VALUES(`discount_desc`), `market_expr` = VALUES(`market_expr`);

-- 3) 活动：24 小时成团窗口、每人最多参与 10 次、2 人成团
INSERT INTO `group_buy_activity` (`activity_id`, `activity_name`, `discount_id`, `group_type`, `take_limit_count`, `target`, `valid_time`, `status`, `start_time`, `end_time`, `tag_id`, `tag_scope`)
VALUES
  (100201,'Pro月卡拼团','25120301',0,10,2,1440,1,'2024-12-07 10:19:40','2030-12-31 23:59:59',NULL,NULL),
  (100202,'Pro年卡拼团','25120302',0,10,2,1440,1,'2024-12-07 10:19:40','2030-12-31 23:59:59',NULL,NULL),
  (100203,'额度加油包拼团','25120303',0,10,2,1440,1,'2024-12-07 10:19:40','2030-12-31 23:59:59',NULL,NULL)
ON DUPLICATE KEY UPDATE `activity_name` = VALUES(`activity_name`), `discount_id` = VALUES(`discount_id`),
  `take_limit_count` = VALUES(`take_limit_count`), `target` = VALUES(`target`), `valid_time` = VALUES(`valid_time`),
  `status` = VALUES(`status`), `end_time` = VALUES(`end_time`);

-- 4) 渠道商品-活动映射
INSERT INTO `sc_sku_activity` (`source`, `channel`, `activity_id`, `goods_id`)
VALUES
  ('s01','c01',100201,'9890002'),
  ('s01','c01',100202,'9890003'),
  ('s01','c01',100203,'9890004')
ON DUPLICATE KEY UPDATE `activity_id` = VALUES(`activity_id`);

-- 5) legacy 活动调参：15 分钟团太短、每人 1 次直接卡死二次开团
UPDATE `group_buy_activity` SET `take_limit_count` = 10, `valid_time` = 1440 WHERE `activity_id` = 100123;
