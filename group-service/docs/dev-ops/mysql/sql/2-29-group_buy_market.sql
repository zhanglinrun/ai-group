# 熊博士拼团数据库初始化
# 仅保留当前额度包业务所需的表结构和基础配置。
# 不预置历史用户、旧标签、测试订单、通知记录或旧商品。

SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS `group_buy_market`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `group_buy_market`;

CREATE TABLE IF NOT EXISTS `crowd_tags` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `tag_id` varchar(32) NOT NULL COMMENT '人群ID',
  `tag_name` varchar(64) NOT NULL COMMENT '人群名称',
  `tag_desc` varchar(256) NOT NULL COMMENT '人群描述',
  `statistics` int NOT NULL COMMENT '人群标签统计量',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tag_id` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人群标签';

CREATE TABLE IF NOT EXISTS `crowd_tags_detail` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `tag_id` varchar(32) NOT NULL COMMENT '人群ID',
  `user_id` varchar(16) NOT NULL COMMENT '用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tag_user` (`tag_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人群标签明细';

CREATE TABLE IF NOT EXISTS `crowd_tags_job` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `tag_id` varchar(32) NOT NULL COMMENT '标签ID',
  `batch_id` varchar(8) NOT NULL COMMENT '批次ID',
  `tag_type` tinyint(1) NOT NULL DEFAULT '1' COMMENT '标签类型（参与量、消费金额）',
  `tag_rule` varchar(8) NOT NULL COMMENT '标签规则（限定类型 N次）',
  `stat_start_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '统计开始时间',
  `stat_end_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '统计结束时间',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '状态；0初始、1计划、2重置、3完成',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_batch_id` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人群标签任务';

CREATE TABLE IF NOT EXISTS `group_buy_activity` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增',
  `activity_id` bigint NOT NULL COMMENT '活动ID',
  `activity_name` varchar(128) NOT NULL COMMENT '活动名称',
  `discount_id` varchar(8) NOT NULL COMMENT '折扣ID',
  `group_type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '拼团方式',
  `take_limit_count` int NOT NULL DEFAULT '1' COMMENT '拼团次数限制',
  `target` int NOT NULL DEFAULT '1' COMMENT '拼团目标',
  `valid_time` int NOT NULL DEFAULT '15' COMMENT '拼团时长（分钟）',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '活动状态',
  `start_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '活动开始时间',
  `end_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '活动结束时间',
  `tag_id` varchar(8) DEFAULT NULL COMMENT '人群标签规则标识',
  `tag_scope` varchar(4) DEFAULT NULL COMMENT '人群标签规则范围',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_activity_id` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拼团活动';

CREATE TABLE IF NOT EXISTS `group_buy_discount` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `discount_id` varchar(8) NOT NULL COMMENT '折扣ID',
  `discount_name` varchar(64) NOT NULL COMMENT '折扣标题',
  `discount_desc` varchar(256) NOT NULL COMMENT '折扣描述',
  `discount_type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '折扣类型',
  `market_plan` varchar(4) NOT NULL DEFAULT 'ZJ' COMMENT '营销优惠计划',
  `market_expr` varchar(32) NOT NULL COMMENT '营销优惠表达式',
  `tag_id` varchar(8) DEFAULT NULL COMMENT '人群标签',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_discount_id` (`discount_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `group_buy_order` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `team_id` varchar(64) NOT NULL COMMENT '拼单组队ID',
  `activity_id` bigint NOT NULL COMMENT '活动ID',
  `source` varchar(8) NOT NULL COMMENT '渠道',
  `channel` varchar(8) NOT NULL COMMENT '来源',
  `original_price` decimal(8,2) NOT NULL COMMENT '原始价格',
  `deduction_price` decimal(8,2) NOT NULL COMMENT '折扣金额',
  `pay_price` decimal(8,2) NOT NULL COMMENT '支付价格',
  `target_count` int NOT NULL COMMENT '目标数量',
  `complete_count` int NOT NULL COMMENT '完成数量',
  `lock_count` int NOT NULL COMMENT '锁单数量',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '拼团状态',
  `valid_start_time` datetime NOT NULL COMMENT '拼团开始时间',
  `valid_end_time` datetime NOT NULL COMMENT '拼团结束时间',
  `notify_type` varchar(8) NOT NULL DEFAULT 'HTTP' COMMENT '回调类型',
  `notify_url` varchar(512) DEFAULT NULL COMMENT '回调地址',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_team_id` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `group_buy_order_list` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `team_id` varchar(64) NOT NULL COMMENT '拼单组队ID',
  `order_id` varchar(64) NOT NULL COMMENT '订单ID',
  `activity_id` bigint NOT NULL COMMENT '活动ID',
  `start_time` datetime NOT NULL COMMENT '活动开始时间',
  `end_time` datetime NOT NULL COMMENT '活动结束时间',
  `goods_id` varchar(16) NOT NULL COMMENT '商品ID',
  `source` varchar(8) NOT NULL COMMENT '渠道',
  `channel` varchar(8) NOT NULL COMMENT '来源',
  `original_price` decimal(8,2) NOT NULL COMMENT '原始价格',
  `deduction_price` decimal(8,2) NOT NULL COMMENT '折扣金额',
  `pay_price` decimal(8,2) NOT NULL COMMENT '支付金额',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '订单状态',
  `out_trade_no` varchar(64) NOT NULL COMMENT '外部交易单号',
  `out_trade_time` datetime DEFAULT NULL COMMENT '外部交易时间',
  `biz_id` varchar(64) NOT NULL COMMENT '业务唯一ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_order_id` (`order_id`),
  UNIQUE KEY `uq_biz_id` (`biz_id`),
  UNIQUE KEY `uq_sc_out_trade_no` (`source`,`channel`,`out_trade_no`),
  KEY `idx_user_id_activity_id` (`user_id`,`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `notify_task` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `activity_id` bigint NOT NULL COMMENT '活动ID',
  `team_id` varchar(64) NOT NULL COMMENT '拼单组队ID',
  `notify_category` varchar(64) DEFAULT NULL COMMENT '回调种类',
  `notify_type` varchar(8) NOT NULL DEFAULT 'HTTP' COMMENT '回调类型',
  `notify_mq` varchar(32) DEFAULT NULL COMMENT '回调消息',
  `notify_url` varchar(128) DEFAULT NULL COMMENT '回调接口',
  `notify_count` int NOT NULL COMMENT '回调次数',
  `notify_status` tinyint(1) NOT NULL COMMENT '回调状态',
  `parameter_json` varchar(256) NOT NULL COMMENT '参数对象',
  `uuid` varchar(128) NOT NULL COMMENT '唯一标识',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_uuid` (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `sc_sku_activity` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `source` varchar(8) NOT NULL COMMENT '渠道',
  `channel` varchar(8) NOT NULL COMMENT '来源',
  `activity_id` bigint NOT NULL COMMENT '活动ID',
  `goods_id` varchar(16) NOT NULL COMMENT '商品ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sc_goodsid` (`source`,`channel`,`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道商品活动关联';

CREATE TABLE IF NOT EXISTS `sku` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `source` varchar(8) NOT NULL COMMENT '渠道',
  `channel` varchar(8) NOT NULL COMMENT '来源',
  `goods_id` varchar(16) NOT NULL COMMENT '商品ID',
  `goods_name` varchar(128) NOT NULL COMMENT '商品名称',
  `original_price` decimal(10,2) NOT NULL COMMENT '商品价格',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_goods_id` (`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品信息';

-- 当前项目的三个额度包商品。
INSERT INTO `sku` (`id`, `source`, `channel`, `goods_id`, `goods_name`, `original_price`)
VALUES
  (1, 's01', 'c01', '9890002', '轻量额度包（60）', 12.00),
  (2, 's01', 'c01', '9890003', '标准额度包（300）', 60.00),
  (3, 's01', 'c01', '9890004', '大额额度包（700）', 140.00);

INSERT INTO `group_buy_discount` (`id`, `discount_id`, `discount_name`, `discount_desc`, `discount_type`, `market_plan`, `market_expr`, `tag_id`)
VALUES
  (1, '25120301', '轻量额度包拼团', '拼团 9 折优惠，3 人成团', 0, 'ZK', '0.90', NULL),
  (2, '25120302', '标准额度包拼团', '拼团 9 折优惠，3 人成团', 0, 'ZK', '0.90', NULL),
  (3, '25120303', '大额额度包拼团', '拼团 9 折优惠，3 人成团', 0, 'ZK', '0.90', NULL);

INSERT INTO `group_buy_activity` (`id`, `activity_id`, `activity_name`, `discount_id`, `group_type`, `take_limit_count`, `target`, `valid_time`, `status`, `start_time`, `end_time`, `tag_id`, `tag_scope`)
VALUES
  (1, 100201, '轻量额度包拼团', '25120301', 0, 10, 3, 1440, 1, CURRENT_TIMESTAMP, '2030-12-31 23:59:59', NULL, NULL),
  (2, 100202, '标准额度包拼团', '25120302', 0, 10, 3, 1440, 1, CURRENT_TIMESTAMP, '2030-12-31 23:59:59', NULL, NULL),
  (3, 100203, '大额额度包拼团', '25120303', 0, 10, 3, 1440, 1, CURRENT_TIMESTAMP, '2030-12-31 23:59:59', NULL, NULL);

INSERT INTO `sc_sku_activity` (`id`, `source`, `channel`, `activity_id`, `goods_id`)
VALUES
  (1, 's01', 'c01', 100201, '9890002'),
  (2, 's01', 'c01', 100202, '9890003'),
  (3, 's01', 'c01', 100203, '9890004');
