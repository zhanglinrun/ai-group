#
# XXL-JOB
# Copyright (c) 2015-present, xuxueli.

CREATE database if NOT EXISTS `xxl_job` default character set utf8mb4 collate utf8mb4_unicode_ci;
use `xxl_job`;

SET NAMES utf8mb4;

## —————————————————————— job group and registry ——————————————————

CREATE TABLE `xxl_job_group`
(
    `id`           int(11)     NOT NULL AUTO_INCREMENT,
    `app_name`     varchar(64) NOT NULL COMMENT '执行器AppName',
    `title`        varchar(64) NOT NULL COMMENT '执行器名称',
    `address_type` tinyint(4)  NOT NULL DEFAULT '0' COMMENT '执行器地址类型：0=自动注册、1=手动录入',
    `address_list` text COMMENT '执行器地址列表，多地址逗号分隔',
    `update_time`  datetime             DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_registry`
(
    `id`                bigint(20)   NOT NULL AUTO_INCREMENT,
    `registry_group`    varchar(50)  NOT NULL,
    `registry_key`      varchar(255) NOT NULL,
    `registry_value`    varchar(255) NOT NULL,
    `update_time`       datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_g_k_v` (`registry_group`, `registry_key`, `registry_value`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

## —————————————————————— job info ——————————————————

CREATE TABLE `xxl_job_info`
(
    `id`                        int(11)      NOT NULL AUTO_INCREMENT,
    `job_group`                 int(11)      NOT NULL COMMENT '执行器主键ID',
    `job_desc`                  varchar(255) NOT NULL,
    `add_time`                  datetime              DEFAULT NULL,
    `update_time`               datetime              DEFAULT NULL,
    `author`                    varchar(64)           DEFAULT NULL COMMENT '作者',
    `alarm_email`               varchar(255)          DEFAULT NULL COMMENT '报警邮件',
    `schedule_type`             varchar(50)  NOT NULL DEFAULT 'NONE' COMMENT '调度类型',
    `schedule_conf`             varchar(128)          DEFAULT NULL COMMENT '调度配置，值含义取决于调度类型',
    `misfire_strategy`          varchar(50)  NOT NULL DEFAULT 'DO_NOTHING' COMMENT '调度过期策略',
    `executor_route_strategy`   varchar(50)           DEFAULT NULL COMMENT '执行器路由策略',
    `executor_handler`          varchar(255)          DEFAULT NULL COMMENT '任务handler',
    `executor_param`            text                  DEFAULT NULL COMMENT '任务参数',
    `executor_block_strategy`   varchar(50)           DEFAULT NULL COMMENT '阻塞处理策略',
    `executor_timeout`          int(11)      NOT NULL DEFAULT '0' COMMENT '任务执行超时时间，单位秒',
    `executor_fail_retry_count` int(11)      NOT NULL DEFAULT '0' COMMENT '失败重试次数',
    `glue_type`                 varchar(50)  NOT NULL COMMENT 'GLUE类型',
    `glue_source`               mediumtext COMMENT 'GLUE源代码',
    `glue_remark`               varchar(128)          DEFAULT NULL COMMENT 'GLUE备注',
    `glue_updatetime`           datetime              DEFAULT NULL COMMENT 'GLUE更新时间',
    `child_jobid`               varchar(255)          DEFAULT NULL COMMENT '子任务ID，多个逗号分隔',
    `trigger_status`            tinyint(4)   NOT NULL DEFAULT '0' COMMENT '调度状态：0-停止，1-运行',
    `trigger_last_time`         bigint(13)   NOT NULL DEFAULT '0' COMMENT '上次调度时间',
    `trigger_next_time`         bigint(13)   NOT NULL DEFAULT '0' COMMENT '下次调度时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_logglue`
(
    `id`          int(11)      NOT NULL AUTO_INCREMENT,
    `job_id`      int(11)      NOT NULL COMMENT '任务，主键ID',
    `glue_type`   varchar(50) DEFAULT NULL COMMENT 'GLUE类型',
    `glue_source` mediumtext COMMENT 'GLUE源代码',
    `glue_remark` varchar(128) NOT NULL COMMENT 'GLUE备注',
    `add_time`    datetime    DEFAULT NULL,
    `update_time` datetime    DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

## —————————————————————— job log and report ——————————————————

CREATE TABLE `xxl_job_log`
(
    `id`                        bigint(20)          NOT NULL AUTO_INCREMENT,
    `job_group`                 int(11)             NOT NULL COMMENT '执行器主键ID',
    `job_id`                    int(11)             NOT NULL COMMENT '任务，主键ID',
    `executor_address`          varchar(255)        DEFAULT NULL COMMENT '执行器地址，本次执行的地址',
    `executor_handler`          varchar(255)        DEFAULT NULL COMMENT '任务handler',
    `executor_param`            text                DEFAULT NULL COMMENT '任务参数',
    `executor_sharding_param`   varchar(20)         DEFAULT NULL COMMENT '任务分片参数，格式如 1/2',
    `executor_fail_retry_count` int(11)             NOT NULL DEFAULT '0' COMMENT '失败重试次数',
    `trigger_time`              datetime            DEFAULT NULL COMMENT '调度-时间',
    `trigger_code`              int(11)             NOT NULL COMMENT '调度-结果',
    `trigger_msg`               text                COMMENT '调度-日志',
    `handle_time`               datetime            DEFAULT NULL COMMENT '执行-时间',
    `handle_code`               int(11)             NOT NULL COMMENT '执行-状态',
    `handle_msg`                text                COMMENT '执行-日志',
    `alarm_status`              tinyint(4)          NOT NULL DEFAULT '0' COMMENT '告警状态：0-默认、1-无需告警、2-告警成功、3-告警失败',
    PRIMARY KEY (`id`),
    KEY `I_trigger_time` (`trigger_time`),
    KEY `I_handle_code` (`handle_code`),
    KEY `I_jobgroup` (`job_group`),
    KEY `I_jobid` (`job_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_log_report`
(
    `id`            int(11) NOT NULL AUTO_INCREMENT,
    `trigger_day`   datetime         DEFAULT NULL COMMENT '调度-时间',
    `running_count` int(11) NOT NULL DEFAULT '0' COMMENT '运行中-日志数量',
    `suc_count`     int(11) NOT NULL DEFAULT '0' COMMENT '执行成功-日志数量',
    `fail_count`    int(11) NOT NULL DEFAULT '0' COMMENT '执行失败-日志数量',
    `update_time`   datetime         DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_trigger_day` (`trigger_day`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

## —————————————————————— lock ——————————————————

CREATE TABLE `xxl_job_lock`
(
    `lock_name` varchar(50) NOT NULL COMMENT '锁名称',
    PRIMARY KEY (`lock_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

## —————————————————————— user ——————————————————

CREATE TABLE `xxl_job_user`
(
    `id`         int(11)     NOT NULL AUTO_INCREMENT,
    `username`   varchar(50) NOT NULL COMMENT '账号',
    `password`   varchar(100) NOT NULL COMMENT '密码加密信息',
    `token`      varchar(100) DEFAULT NULL COMMENT '登录token',
    `role`       tinyint(4)  NOT NULL COMMENT '角色：0-普通用户、1-管理员',
    `permission` varchar(255) DEFAULT NULL COMMENT '权限：执行器ID列表，多个逗号分割',
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_username` (`username`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;



## —————————————————————— for default data ——————————————————

INSERT INTO xxl_job_group(id, pp_name, 	itle, ddress_type, ddress_list, update_time)
VALUES
  (1, 'pay', 'Pay 执行器', 0, NULL, now()),
  (2, 'group', 'Group 执行器', 0, NULL, now()),
  (3, 'member', 'Member 执行器', 0, NULL, now()),
  (4, 'auth', 'Auth 执行器', 0, NULL, now());

INSERT INTO xxl_job_info(id, job_group, job_desc, dd_time, update_time, uthor, larm_email,
                           schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
                           executor_handler, executor_param, executor_block_strategy, executor_timeout,
                           executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
                           child_jobid, 	rigger_status, 	rigger_last_time, 	rigger_next_time)
VALUES
  (1, 1, 'Pay Outbox 投递', now(), now(), 'xiongdoctor', '', 'CRON', '0/1 * * * * ?',
   'DO_NOTHING', 'FIRST', 'outboxEventPublishJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0),
  (2, 1, 'Pay 未支付通知补偿', now(), now(), 'xiongdoctor', '', 'CRON', '0 0/1 * * * ?',
   'DO_NOTHING', 'FIRST', 'noPayNotifyOrderJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0),
  (3, 1, 'Pay 超时关单', now(), now(), 'xiongdoctor', '', 'CRON', '0 0/1 * * * ?',
   'DO_NOTHING', 'FIRST', 'timeoutCloseOrderJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0),
  (4, 1, 'Pay 待退款补偿', now(), now(), 'xiongdoctor', '', 'CRON', '0 0/1 * * * ?',
   'DO_NOTHING', 'FIRST', 'waitRefundCompensateJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0),
  (5, 1, 'Pay 拼团结算补偿', now(), now(), 'xiongdoctor', '', 'CRON', '0 0/1 * * * ?',
   'DO_NOTHING', 'FIRST', 'marketSettlementCompensateJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0),
  (6, 2, 'Group 成团通知', now(), now(), 'xiongdoctor', '', 'CRON', '0 0/1 * * * ?',
   'DO_NOTHING', 'FIRST', 'groupBuyNotifyJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0),
  (7, 2, 'Group 超时退款', now(), now(), 'xiongdoctor', '', 'CRON', '0 0/1 * * * ?',
   'DO_NOTHING', 'FIRST', 'timeoutRefundJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0),
  (8, 3, 'Member 过期冻结释放', now(), now(), 'xiongdoctor', '', 'CRON', '0 0/5 * * * ?',
   'DO_NOTHING', 'FIRST', 'expiredFreezeReleaseJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0),
  (9, 3, 'Member 月度额度发放', now(), now(), 'xiongdoctor', '', 'CRON', '0 0 1 1 * ?',
   'DO_NOTHING', 'FIRST', 'monthlyQuotaGrantJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0),
  (10, 4, 'Auth Outbox 投递', now(), now(), 'xiongdoctor', '', 'CRON', '0/1 * * * * ?',
   'DO_NOTHING', 'FIRST', 'authOutboxPublishJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化',
   now(), '', 1, 0, 0);

-- admin / 123456 (SHA256)
INSERT INTO xxl_job_user(id, username, password, 
ole, permission)
VALUES (1, 'admin', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 1, NULL);

INSERT INTO xxl_job_lock (lock_name)
VALUES ('schedule_lock');

commit;
