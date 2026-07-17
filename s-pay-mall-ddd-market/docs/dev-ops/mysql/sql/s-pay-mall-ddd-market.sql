# ************************************************************
# Sequel Ace SQL dump
# 版本号： 20050
#
# https://sequel-ace.com/
# https://github.com/Sequel-Ace/Sequel-Ace
#
# 主机: 127.0.0.1 (MySQL 5.6.39)
# 数据库: s-pay-mall-ddd-market
# 生成时间: 2025-02-06 09:26:46 +0000
# ************************************************************


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
SET NAMES utf8mb4;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE='NO_AUTO_VALUE_ON_ZERO', SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

CREATE database if NOT EXISTS `s_pay_mall_ddd_market` default character set utf8mb4 ;
use `s_pay_mall_ddd_market`;

# 转储表 pay_order
# ------------------------------------------------------------

DROP TABLE IF EXISTS `pay_order`;

CREATE TABLE `pay_order` (
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
  `base_quota_snapshot` bigint NOT NULL DEFAULT 0 COMMENT '下单时基础额度快照（整额度点）',
  `order_id` varchar(16) NOT NULL COMMENT '订单ID',
  `order_time` datetime NOT NULL COMMENT '下单时间',
  `total_amount` decimal(8,2) unsigned DEFAULT NULL COMMENT '订单金额',
  `status` varchar(32) NOT NULL COMMENT '订单状态；create-创建完成、pay_wait-等待支付、pay_success-支付成功、deal_done-交易完成、close-订单关单',
  `pay_url` varchar(2014) DEFAULT NULL COMMENT '支付信息',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `market_type` tinyint(1) DEFAULT NULL COMMENT '营销类型；0无营销、1拼团营销',
  `group_activity_id` bigint DEFAULT NULL COMMENT '下单时拼团活动ID快照，直购为空',
  `group_team_id` varchar(64) DEFAULT NULL COMMENT '下单时拼团队伍ID快照，开团为空',
  `market_deduction_amount` decimal(8,2) DEFAULT NULL COMMENT '营销金额；优惠金额',
  `pay_amount` decimal(8,2) NOT NULL COMMENT '支付金额',
  `settlement_notified` tinyint(1) NOT NULL DEFAULT 0 COMMENT '拼团结算是否已通知成功；1=group已确认登记，补偿任务据此区分"未结算"与"未成团"',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_order_id` (`order_id`),
  UNIQUE KEY `uq_user_client_request` (`user_id`,`client_request_id`),
  KEY `idx_user_id_product_id` (`user_id`,`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

LOCK TABLES `pay_order` WRITE;
/*!40000 ALTER TABLE `pay_order` DISABLE KEYS */;

INSERT INTO `pay_order` (`id`, `user_id`, `product_id`, `product_name`, `order_id`, `order_time`, `total_amount`, `status`, `pay_url`, `pay_time`, `market_type`, `market_deduction_amount`, `pay_amount`, `create_time`, `update_time`)
VALUES
	(11,'xiaofuge01','9890001','MyBatisBook','750681536632','2025-02-06 16:52:47',1.68,'PAY_WAIT','<form name=\"punchout_form\" method=\"post\" action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do?charset=utf-8&method=alipay.trade.page.pay&sign=I1TPyoZd45%2Bv6k4ka399jJO%2FrGF%2BAwrbPS%2FsLKCi7X9cMk5sxF2tTAkjRyKdDLKe9Xe0Dwycv2u8KuRSPqoxXGLqqAWdnIQiRnBuRA9ipDjqNN%2BxG3wvE8brfoG27c%2BehuznktexibaAvAGTjKsEy%2F33lEiUGZgN80uNvsMCrbnRTApvX9T4E3H3hfFrAGKstr5umM72O0fzsygBQZnvhSuOLJEU5FFxYwBIBVaDvzwyreCOIQ05AhGhT94v6WNEuoQn8BGxEklP6VATMeVDwRQIyVJ12dx3k0WyBVM%2FzAHe3Q7CIMAdbpOGBtUWc%2Fm0c%2BuRtZ3Fyl3V4bUdzNtptg%3D%3D&return_url=https%3A%2F%2Fgaga.plus&notify_url=http%3A%2F%2Fxfg-studio.natapp1.cc%2Fapi%2Fv1%2Falipay%2Falipay_notify_url&version=1.0&app_id=9021000132689924&sign_type=RSA2&timestamp=2025-02-06+16%3A52%3A48&alipay_sdk=alipay-sdk-java-4.38.157.ALL&format=json\">\n<input type=\"hidden\" name=\"biz_content\" value=\"{&quot;out_trade_no&quot;:&quot;750681536632&quot;,&quot;total_amount&quot;:90.00,&quot;subject&quot;:&quot;MyBatisBook&quot;,&quot;product_code&quot;:&quot;FAST_INSTANT_TRADE_PAY&quot;}\">\n<input type=\"submit\" value=\"立即支付\" style=\"display:none\" >\n</form>\n<script>document.forms[0].submit();</script>',NULL,1,10.00,90.00,'2025-02-06 16:52:47','2025-02-06 16:52:48'),
	(12,'xiaofuge02','9890001','MyBatisBook','556269893069','2025-02-06 16:56:11',100.00,'PAY_WAIT','<form name=\"punchout_form\" method=\"post\" action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do?charset=utf-8&method=alipay.trade.page.pay&sign=Br0nTsfQfAC9p1VvKICvwRhbp0j%2B4OQ5fJBs2dB6Mb9K0u9V083lfgM6Gb4Ob9qthtz0a%2BsaOWlXLx4TFvg7%2Flk8QUsmR4%2Bs%2F6VO8%2B9vMrjRzsi8ZfniZfhnhi7KbIAqN6VWl1kWpEyQ9hWdWTf36znyVUvXcAzuc75e8qKyxMhtBlVsjDTb7Zll1KkYRgNHVNxiJZ%2F7OeHncaMN7M3oqjxuvROt21V0j3le6%2Flit7ZJSmhKYw6Fq2CvC2nuDK21i67TKqo%2Bs5%2FRasgiglkXw24AtO8%2Fs3BL5MHPgmsULRuxIRhEaXR97pZuIobUQAi5ssb%2BE4Vy1r8QYxxvbXNQPA%3D%3D&return_url=https%3A%2F%2Fgaga.plus&notify_url=http%3A%2F%2Fxfg-studio.natapp1.cc%2Fapi%2Fv1%2Falipay%2Falipay_notify_url&version=1.0&app_id=9021000132689924&sign_type=RSA2&timestamp=2025-02-06+16%3A56%3A11&alipay_sdk=alipay-sdk-java-4.38.157.ALL&format=json\">\n<input type=\"hidden\" name=\"biz_content\" value=\"{&quot;out_trade_no&quot;:&quot;556269893069&quot;,&quot;total_amount&quot;:90.00,&quot;subject&quot;:&quot;MyBatisBook&quot;,&quot;product_code&quot;:&quot;FAST_INSTANT_TRADE_PAY&quot;}\">\n<input type=\"submit\" value=\"立即支付\" style=\"display:none\" >\n</form>\n<script>document.forms[0].submit();</script>',NULL,1,10.00,90.00,'2025-02-06 16:56:10','2025-02-06 16:56:11');

/*!40000 ALTER TABLE `pay_order` ENABLE KEYS */;
UNLOCK TABLES;



/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
