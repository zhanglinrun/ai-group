-- Run against an existing member_db before enabling quotaReconciliationJob.
USE `member_db`;

CREATE TABLE IF NOT EXISTS `quota_reconciliation_mismatch` (
    `check_date` DATE NOT NULL,
    `user_id` BIGINT NOT NULL,
    `check_type` VARCHAR(32) NOT NULL,
    `snapshot_balance` BIGINT NOT NULL,
    `source_balance` BIGINT NOT NULL,
    `first_seen_at` DATETIME NOT NULL,
    `last_seen_at` DATETIME NOT NULL,
    PRIMARY KEY (`check_date`, `user_id`, `check_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
