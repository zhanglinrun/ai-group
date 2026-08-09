-- UUID-backed team/order identifiers. Safe to re-run on existing local databases.
USE `group_buy_market`;

ALTER TABLE `group_buy_order`
    MODIFY COLUMN `team_id` VARCHAR(64) NOT NULL COMMENT '拼单组队ID';

ALTER TABLE `group_buy_order_list`
    MODIFY COLUMN `team_id` VARCHAR(64) NOT NULL COMMENT '拼单组队ID',
    MODIFY COLUMN `order_id` VARCHAR(64) NOT NULL COMMENT '订单ID',
    MODIFY COLUMN `out_trade_no` VARCHAR(64) NOT NULL COMMENT '外部交易单号-确保外部调用唯一幂等';

ALTER TABLE `notify_task`
    MODIFY COLUMN `team_id` VARCHAR(64) NOT NULL COMMENT '拼单组队ID';
