package com.linrun.agent.trigger.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.service.task.ITaskService;
import com.linrun.agent.domain.agent.model.valobj.AiAgentTaskScheduleVO;
import com.linrun.agent.types.job.model.TaskScheduleVO;
import com.linrun.agent.types.job.provider.ITaskDataProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体定时任务数据提供者：把有效调度绑定到可执行逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskJob implements ITaskDataProvider {

    private final ITaskService taskService;
    private final ScheduledAgentTaskExecutor scheduledAgentTaskExecutor;

    @Override
    public List<TaskScheduleVO> queryAllValidTaskSchedule() {
        List<AiAgentTaskScheduleVO> schedules = taskService.queryAllValidTaskSchedule();
        List<TaskScheduleVO> result = new ArrayList<>();
        if (schedules == null || schedules.isEmpty()) {
            return result;
        }
        for (AiAgentTaskScheduleVO schedule : schedules) {
            if (!scheduledAgentTaskExecutor.isSchedulable(schedule)) {
                log.warn("跳过非法定时任务配置 id={} agentId={} cron={} taskParamBlank={}",
                        schedule == null ? null : schedule.getId(),
                        schedule == null ? null : schedule.getAgentId(),
                        schedule == null ? null : schedule.getCronExpression(),
                        schedule == null || schedule.getTaskParam() == null || schedule.getTaskParam().isBlank());
                continue;
            }
            TaskScheduleVO taskScheduleVO = new TaskScheduleVO();
            taskScheduleVO.setId(schedule.getId());
            taskScheduleVO.setDescription(schedule.getDescription());
            taskScheduleVO.setCronExpression(schedule.getCronExpression());
            taskScheduleVO.setTaskParam(schedule.getTaskParam());
            taskScheduleVO.setTaskLogic((taskId, taskParam) ->
                    scheduledAgentTaskExecutor.execute(schedule, taskParam));
            result.add(taskScheduleVO);
        }
        return result;
    }

    @Override
    public List<Long> queryAllInvalidTaskScheduleIds() {
        return taskService.queryAllInvalidTaskScheduleIds();
    }
}
