package com.linrun.agent.domain.agent.service.task;

import com.linrun.agent.domain.agent.model.valobj.AiAgentTaskScheduleVO;

import java.util.List;

/**
 * Agent 领域任务接口。
 */
public interface ITaskService {

    List<AiAgentTaskScheduleVO> queryAllValidTaskSchedule();

    List<Long> queryAllInvalidTaskScheduleIds();
}
