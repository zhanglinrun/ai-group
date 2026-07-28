CREATE DATABASE IF NOT EXISTS `xxl_job` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `xxl_job`;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `xxl_job_group` (
  `id` int NOT NULL AUTO_INCREMENT,
  `app_name` varchar(64) NOT NULL,
  `title` varchar(64) NOT NULL,
  `address_type` tinyint NOT NULL DEFAULT 0,
  `address_list` text,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `xxl_job_registry` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `registry_group` varchar(50) NOT NULL,
  `registry_key` varchar(255) NOT NULL,
  `registry_value` varchar(255) NOT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `i_g_k_v` (`registry_group`,`registry_key`,`registry_value`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `xxl_job_info` (
  `id` int NOT NULL AUTO_INCREMENT,
  `job_group` int NOT NULL,
  `job_desc` varchar(255) NOT NULL,
  `add_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `author` varchar(64) DEFAULT NULL,
  `alarm_email` varchar(255) DEFAULT NULL,
  `schedule_type` varchar(50) NOT NULL DEFAULT 'NONE',
  `schedule_conf` varchar(128) DEFAULT NULL,
  `misfire_strategy` varchar(50) NOT NULL DEFAULT 'DO_NOTHING',
  `executor_route_strategy` varchar(50) DEFAULT NULL,
  `executor_handler` varchar(255) DEFAULT NULL,
  `executor_param` text,
  `executor_block_strategy` varchar(50) DEFAULT NULL,
  `executor_timeout` int NOT NULL DEFAULT 0,
  `executor_fail_retry_count` int NOT NULL DEFAULT 0,
  `glue_type` varchar(50) NOT NULL,
  `glue_source` mediumtext,
  `glue_remark` varchar(128) DEFAULT NULL,
  `glue_updatetime` datetime DEFAULT NULL,
  `child_jobid` varchar(255) DEFAULT NULL,
  `trigger_status` tinyint NOT NULL DEFAULT 0,
  `trigger_last_time` bigint NOT NULL DEFAULT 0,
  `trigger_next_time` bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `xxl_job_logglue` (
  `id` int NOT NULL AUTO_INCREMENT,
  `job_id` int NOT NULL,
  `glue_type` varchar(50) DEFAULT NULL,
  `glue_source` mediumtext,
  `glue_remark` varchar(128) NOT NULL,
  `add_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `xxl_job_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_group` int NOT NULL,
  `job_id` int NOT NULL,
  `executor_address` varchar(255) DEFAULT NULL,
  `executor_handler` varchar(255) DEFAULT NULL,
  `executor_param` text,
  `executor_sharding_param` varchar(20) DEFAULT NULL,
  `executor_fail_retry_count` int NOT NULL DEFAULT 0,
  `trigger_time` datetime DEFAULT NULL,
  `trigger_code` int NOT NULL,
  `trigger_msg` text,
  `handle_time` datetime DEFAULT NULL,
  `handle_code` int NOT NULL,
  `handle_msg` text,
  `alarm_status` tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `I_trigger_time` (`trigger_time`),
  KEY `I_handle_code` (`handle_code`),
  KEY `I_jobgroup` (`job_group`),
  KEY `I_jobid` (`job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `xxl_job_log_report` (
  `id` int NOT NULL AUTO_INCREMENT,
  `trigger_day` datetime DEFAULT NULL,
  `running_count` int NOT NULL DEFAULT 0,
  `suc_count` int NOT NULL DEFAULT 0,
  `fail_count` int NOT NULL DEFAULT 0,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `i_trigger_day` (`trigger_day`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `xxl_job_lock` (
  `lock_name` varchar(50) NOT NULL,
  PRIMARY KEY (`lock_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `xxl_job_user` (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password` varchar(100) NOT NULL,
  `token` varchar(100) DEFAULT NULL,
  `role` tinyint NOT NULL,
  `permission` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `i_username` (`username`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `xxl_job_user` (`id`,`username`,`password`,`role`,`permission`)
VALUES (1,'admin','8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92',1,NULL)
ON DUPLICATE KEY UPDATE `role`=VALUES(`role`),
  `password`=IF(`password`='e10adc3949ba59abbe56e057f20f883e',VALUES(`password`),`password`);
INSERT INTO `xxl_job_lock` (`lock_name`) VALUES ('schedule_lock')
ON DUPLICATE KEY UPDATE `lock_name`=VALUES(`lock_name`);

INSERT INTO `xxl_job_group` (`id`,`app_name`,`title`,`address_type`,`address_list`,`update_time`) VALUES
  (1,'group','Group service',0,NULL,NOW()),
  (2,'pay','Pay service',0,NULL,NOW()),
  (3,'member','Member service',0,NULL,NOW()),
  (4,'ai-agent','AI agent service',0,NULL,NOW())
ON DUPLICATE KEY UPDATE `app_name`=VALUES(`app_name`),`title`=VALUES(`title`),`update_time`=NOW();

INSERT INTO `xxl_job_info`
(`id`,`job_group`,`job_desc`,`add_time`,`update_time`,`author`,`alarm_email`,`schedule_type`,`schedule_conf`,
 `misfire_strategy`,`executor_route_strategy`,`executor_handler`,`executor_param`,`executor_block_strategy`,
 `executor_timeout`,`executor_fail_retry_count`,`glue_type`,`glue_source`,`glue_remark`,`glue_updatetime`,
 `child_jobid`,`trigger_status`,`trigger_last_time`,`trigger_next_time`) VALUES
 (101,1,'Group notify',NOW(),NOW(),'ai-group','','CRON','0 0/1 * * * ?','DO_NOTHING','FIRST','groupBuyNotifyJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (102,1,'Timeout refund',NOW(),NOW(),'ai-group','','CRON','0 0/1 * * * ?','DO_NOTHING','FIRST','timeoutRefundJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (103,2,'Outbox publish',NOW(),NOW(),'ai-group','','CRON','0/1 * * * * ?','DO_NOTHING','FIRST','outboxEventPublishJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (104,2,'Timeout close order',NOW(),NOW(),'ai-group','','CRON','0 5/30 * * * ?','DO_NOTHING','FIRST','timeoutCloseOrderJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (105,2,'No-pay callback recovery',NOW(),NOW(),'ai-group','','CRON','0 0/30 * * * ?','DO_NOTHING','FIRST','noPayNotifyOrderJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (106,2,'Wait-refund compensation',NOW(),NOW(),'ai-group','','CRON','0 0/1 * * * ?','DO_NOTHING','FIRST','waitRefundCompensateJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (107,2,'Market settlement compensation',NOW(),NOW(),'ai-group','','CRON','0 0/1 * * * ?','DO_NOTHING','FIRST','marketSettlementCompensateJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (108,3,'Expired freeze release',NOW(),NOW(),'ai-group','','CRON','0 0/5 * * * ?','DO_NOTHING','FIRST','expiredFreezeReleaseJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (109,3,'Monthly quota grant',NOW(),NOW(),'ai-group','','CRON','0 0 0 1 * ?','DO_NOTHING','FIRST','monthlyQuotaGrantJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (110,4,'Agent task refresh',NOW(),NOW(),'ai-group','','CRON','0 0/1 * * * ?','DO_NOTHING','FIRST','agentTaskRefreshJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0),
 (111,4,'Agent task cleanup',NOW(),NOW(),'ai-group','','CRON','0 0/10 * * * ?','DO_NOTHING','FIRST','agentTaskCleanupJob','','SERIAL_EXECUTION',0,0,'BEAN','','GLUE code initialized',NOW(),'',0,0,0)
ON DUPLICATE KEY UPDATE
 `job_group`=VALUES(`job_group`),`job_desc`=VALUES(`job_desc`),`update_time`=NOW(),
 `schedule_type`=VALUES(`schedule_type`),`schedule_conf`=VALUES(`schedule_conf`),
 `misfire_strategy`='DO_NOTHING',`executor_route_strategy`='FIRST',
 `executor_handler`=VALUES(`executor_handler`),`executor_block_strategy`='SERIAL_EXECUTION',
 `glue_type`='BEAN';
