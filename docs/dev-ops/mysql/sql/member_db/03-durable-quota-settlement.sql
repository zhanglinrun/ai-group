USE `member_db`;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quota_freeze' AND COLUMN_NAME = 'requested_amount') = 0,
    'ALTER TABLE `quota_freeze` ADD COLUMN `requested_amount` BIGINT DEFAULT NULL AFTER `settled_amount`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quota_freeze' AND COLUMN_NAME = 'min_amount') = 0,
    'ALTER TABLE `quota_freeze` ADD COLUMN `min_amount` BIGINT DEFAULT NULL AFTER `requested_amount`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quota_freeze' AND COLUMN_NAME = 'request_fingerprint') = 0,
    'ALTER TABLE `quota_freeze` ADD COLUMN `request_fingerprint` VARCHAR(64) DEFAULT NULL AFTER `request_id`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quota_freeze' AND COLUMN_NAME = 'owner_service') = 0,
    'ALTER TABLE `quota_freeze` ADD COLUMN `owner_service` VARCHAR(64) DEFAULT NULL AFTER `request_fingerprint`',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'quota_freeze' AND INDEX_NAME = 'idx_managed_expiry') = 0,
    'ALTER TABLE `quota_freeze` ADD KEY `idx_managed_expiry` (`owner_service`, `status`, `created_at`)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
