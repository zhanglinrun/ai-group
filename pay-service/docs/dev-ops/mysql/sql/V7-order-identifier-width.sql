-- Pay order IDs are UUID-backed and must fit the cross-service identifier contract.
USE `s_pay_mall_ddd_market`;

ALTER TABLE `pay_order`
    MODIFY COLUMN `order_id` VARCHAR(64) NOT NULL COMMENT '订单ID';
