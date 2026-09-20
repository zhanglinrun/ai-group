-- Register the daily Member reconciliation handler on an existing XXL-JOB database.
-- Safe to re-run; does not alter existing job scheduling or executor IDs.
USE `xxl_job`;

INSERT INTO xxl_job_info(
    job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
    executor_handler, executor_param, executor_block_strategy, executor_timeout,
    executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
    child_jobid, trigger_status, trigger_last_time, trigger_next_time
)
SELECT g.id, 'Member 每日额度对账', NOW(), NOW(), 'xiongdoctor', '',
       'CRON', '0 30 2 * * ?', 'DO_NOTHING', 'FIRST',
       'quotaReconciliationJob', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', 'GLUE代码初始化', NOW(),
       '', 1, 0, 0
FROM xxl_job_group g
WHERE g.app_name = 'member'
  AND NOT EXISTS (
      SELECT 1 FROM xxl_job_info i WHERE i.executor_handler = 'quotaReconciliationJob'
  );
