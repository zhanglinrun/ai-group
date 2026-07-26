-- 阶梯拼团结算迁移（幂等，安全重跑）。MySQL 8.x。
-- 规则：容量 = 最高档人数(10)，成团门槛 = 最低档(3，由结算逻辑判定)。
--   · 团满最高档(10) → 支付结算即成团发放；
--   · 到期(valid_end_time)已达最低档(≥3) → 按已达档位一次性发放（TimeoutRefundJob 触发）；
--   · 到期不足最低档(<3) → 团不成立，走退款链路。
-- 因此把额度包 SKU 拼团活动的目标人数(=容量)提升到最高档人数。
USE `group_buy_market`;

UPDATE `group_buy_activity` SET `target` = 10 WHERE `activity_id` IN (100201,100202,100203);
