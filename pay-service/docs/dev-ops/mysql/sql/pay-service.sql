# 熊博士支付数据库初始化
# 仅创建当前支付订单表，不预置历史订单或第三方支付回调数据。

SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS `s_pay_mall_ddd_market`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `s_pay_mall_ddd_market`;

CREATE TABLE IF NOT EXISTS `pay_order` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `client_request_id` varchar(64) DEFAULT NULL COMMENT '客户端单次购买请求号，新订单必填',
  `request_fingerprint` char(64) DEFAULT NULL COMMENT '规范化下单载荷 SHA-256',
  `create_stage` varchar(32) NOT NULL DEFAULT 'PREPAY_READY' COMMENT 'durable 下单创建阶段',
  `create_owner_token` varchar(64) DEFAULT NULL COMMENT '创建续作 owner token',
  `create_lease_until` datetime DEFAULT NULL COMMENT '创建续作租约截止时间',
  `user_id` varchar(32) NOT NULL COMMENT '用户ID',
  `product_id` varchar(16) NOT NULL COMMENT '商品ID',
  `product_code` varchar(64) DEFAULT NULL COMMENT 'member SKU code',
  `product_name` varchar(64) NOT NULL COMMENT '商品名称',
  `base_quota_snapshot` bigint NOT NULL DEFAULT 0 COMMENT '下单时基础额度快照',
  `order_id` varchar(64) NOT NULL COMMENT '订单ID',
  `order_time` datetime NOT NULL COMMENT '下单时间',
  `total_amount` decimal(8,2) unsigned DEFAULT NULL COMMENT '订单金额',
  `status` varchar(32) NOT NULL COMMENT '订单状态',
  `pay_url` varchar(2014) DEFAULT NULL COMMENT '支付信息',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `market_type` tinyint(1) DEFAULT NULL COMMENT '营销类型',
  `group_activity_id` bigint DEFAULT NULL COMMENT '拼团活动ID快照',
  `group_team_id` varchar(64) DEFAULT NULL COMMENT '拼团队伍ID快照',
  `market_deduction_amount` decimal(8,2) DEFAULT NULL COMMENT '营销优惠金额',
  `pay_amount` decimal(8,2) NOT NULL COMMENT '支付金额',
  `settlement_notified` tinyint(1) NOT NULL DEFAULT 0 COMMENT '拼团结算通知状态',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_order_id` (`order_id`),
  UNIQUE KEY `uq_user_client_request` (`user_id`,`client_request_id`),
  KEY `idx_user_id_product_id` (`user_id`,`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
