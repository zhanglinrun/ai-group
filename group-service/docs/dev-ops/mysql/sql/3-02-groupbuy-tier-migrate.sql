-- Idempotent compatibility migration (safe to re-run). MySQL 8.x.
--
-- The original prototype used an activity-tier table to change the amount of
-- quota by team size. The current product uses one fixed quota per SKU and
-- ordinary marketing rules (ZJ/MJ/ZK/N), so the legacy table is retained only
-- for old order snapshots and API compatibility. It is intentionally empty.
SET NAMES utf8mb4;
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

-- Current activities are fixed-quota group purchases, not tiered purchases.
UPDATE `group_buy_activity`
SET `activity_type` = 0
WHERE `activity_id` IN (100201,100202,100203);

-- Remove prototype tier rows when this migration is applied to an existing
-- development volume. Historical order snapshots remain untouched.
DELETE FROM `group_buy_activity_tier`;
