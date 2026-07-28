package com.linrun.agent.types.job;

import com.linrun.agent.types.job.config.TaskJobAutoProperties;
import com.linrun.agent.types.job.service.ITaskJobService;
import com.xxl.job.core.handler.annotation.XxlJob;

/**
 * 任务调度作业
 * 定时获取有效的任务调度配置，并动态创建新的任务
 *
 */
public class TaskJob {

    private final TaskJobAutoProperties properties;
    private final ITaskJobService taskJobService;

    public TaskJob(TaskJobAutoProperties properties, ITaskJobService taskJobService) {
        this.properties = properties;
        this.taskJobService = taskJobService;
    }

    /**
     * 定时刷新任务调度配置
     */
    @XxlJob("agentTaskRefreshJob")
    public void refreshTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        taskJobService.refreshTasks();
    }

    /**
     * 定时清理无效任务
     */
    @XxlJob("agentTaskCleanupJob")
    public void cleanInvalidTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        taskJobService.cleanInvalidTasks();
    }

}
