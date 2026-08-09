-- Idempotent per-SKU group-buy migration (safe to re-run).
-- 为每个额度包 SKU 建立独立拼团链：goods(sku) + activity + 渠道映射。
SET NAMES utf8mb4;
USE `group_buy_market`;

-- 1) 商品（goods）
INSERT INTO `sku` (`source`, `channel`, `goods_id`, `goods_name`, `original_price`)
VALUES
  ('s01','c01','9890002','轻量额度包（60）',12.00),
  ('s01','c01','9890003','标准额度包（300）',60.00),
  ('s01','c01','9890004','大额额度包（700）',140.00)
ON DUPLICATE KEY UPDATE `goods_name` = VALUES(`goods_name`), `original_price` = VALUES(`original_price`);

-- 2) 拼团价低于直购价，默认 9 折；优惠类型和折扣表达式可由管理端调整
INSERT INTO `group_buy_discount` (`discount_id`, `discount_name`, `discount_desc`, `discount_type`, `market_plan`, `market_expr`, `tag_id`)
VALUES
  ('25120301','轻量额度包拼团','拼团 9 折优惠，3 人成团',0,'ZK','0.90',NULL),
  ('25120302','标准额度包拼团','拼团 9 折优惠，3 人成团',0,'ZK','0.90',NULL),
  ('25120303','大额额度包拼团','拼团 9 折优惠，3 人成团',0,'ZK','0.90',NULL)
ON DUPLICATE KEY UPDATE `discount_name` = VALUES(`discount_name`), `discount_desc` = VALUES(`discount_desc`),
  `market_plan` = VALUES(`market_plan`), `market_expr` = VALUES(`market_expr`);

-- 3) 活动：24 小时窗口、每人最多参与 10 次，默认 3 人成团
INSERT INTO `group_buy_activity` (`activity_id`, `activity_name`, `discount_id`, `group_type`, `take_limit_count`, `target`, `valid_time`, `status`, `start_time`, `end_time`, `tag_id`, `tag_scope`)
VALUES
  (100201,'轻量额度包拼团','25120301',0,10,3,1440,1,CURRENT_TIMESTAMP,'2030-12-31 23:59:59',NULL,NULL),
  (100202,'标准额度包拼团','25120302',0,10,3,1440,1,CURRENT_TIMESTAMP,'2030-12-31 23:59:59',NULL,NULL),
  (100203,'大额额度包拼团','25120303',0,10,3,1440,1,CURRENT_TIMESTAMP,'2030-12-31 23:59:59',NULL,NULL)
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
