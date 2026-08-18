-- Set default team size for seeded activities. Safe to re-run.
SET NAMES utf8mb4;
USE `group_buy_market`;

UPDATE `group_buy_activity` SET `target` = 3 WHERE `activity_id` IN (100201,100202,100203);
