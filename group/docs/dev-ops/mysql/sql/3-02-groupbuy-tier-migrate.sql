-- Idempotent 阶梯拼团 (tiered group-buy) migration (safe to re-run). MySQL 8.x.
-- 为拼团活动引入"人数档位 → 加赠额度"的阶梯模型：
--   1) group_buy_activity 增加 activity_type（0经典折扣、1阶梯额度）
--   2) 新增 group_buy_activity_tier（每个活动的档位：3人/5人/10人 → 累计加赠额度）
--   3) 把额度包 SKU 对应的活动 100201/100202/100203 配为阶梯活动并写入档位
-- 说明：bonus_quota 为"相对基础额度的累计加赠"，基础额度取自 member_db.product_sku.base_quota。
--       本迁移只做数据模型与读路径所需数据，不改动结算/发放逻辑（结算见后续阶段）。
USE `group_buy_market`;

-- 团队级档位快照：运营修改活动档位只影响之后创建的新团
SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = 'group_buy_market' AND TABLE_NAME = 'group_buy_order' AND COLUMN_NAME = 'tier_snapshot') = 0,
    'ALTER TABLE `group_buy_order` ADD COLUMN `tier_snapshot` text DEFAULT NULL COMMENT ''tier rules captured at team creation'' AFTER `target_count`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1) group_buy_activity.activity_type（幂等新增列）
SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = 'group_buy_market' AND TABLE_NAME = 'group_buy_activity' AND COLUMN_NAME = 'activity_type') = 0,
    'ALTER TABLE `group_buy_activity` ADD COLUMN `activity_type` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''活动类型（0经典折扣拼团、1阶梯额度拼团）'' AFTER `group_type`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 阶梯档位表
CREATE TABLE IF NOT EXISTS `group_buy_activity_tier` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `activity_id` bigint NOT NULL COMMENT '活动ID',
  `tier_no` int NOT NULL COMMENT '档位序号（1..N，按人数升序）',
  `tier_name` varchar(32) NOT NULL COMMENT '档位名称，如 3人团/5人团/10人团',
  `target_count` int NOT NULL COMMENT '达成该档位所需的成团人数',
  `bonus_quota` int NOT NULL DEFAULT '0' COMMENT '达成该档位时相对基础额度的累计加赠额度（叠加在 SKU 基础额度之上）',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '状态（0停用、1生效）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_activity_tier_no` (`activity_id`,`tier_no`),
  KEY `idx_activity_id` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拼团阶梯档位（人数→加赠额度）';

-- 3) 把额度包 SKU 的活动配为阶梯活动
UPDATE `group_buy_activity` SET `activity_type` = 1 WHERE `activity_id` IN (100201,100202,100203);

-- 4) 档位数据（累计加赠额度）：3人+10%、5人+20%、10人+30%
INSERT INTO `group_buy_activity_tier` (`activity_id`, `tier_no`, `tier_name`, `target_count`, `bonus_quota`, `status`)
VALUES
  (100201,1,'3人团',3,6,1),(100201,2,'5人团',5,12,1),(100201,3,'10人团',10,18,1),
  (100202,1,'3人团',3,30,1),(100202,2,'5人团',5,60,1),(100202,3,'10人团',10,90,1),
  (100203,1,'3人团',3,70,1),(100203,2,'5人团',5,140,1),(100203,3,'10人团',10,210,1)
ON DUPLICATE KEY UPDATE
  `tier_name` = VALUES(`tier_name`),
  `target_count` = VALUES(`target_count`),
  `bonus_quota` = VALUES(`bonus_quota`),
  `status` = VALUES(`status`);
