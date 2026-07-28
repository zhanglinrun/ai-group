package com.linrun.agent.domain.agent.service.task;

import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.adapter.repository.IAgentRepository;
import com.linrun.agent.domain.agent.model.valobj.AiAgentTaskScheduleVO;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * Agent 领域任务查询服务。
 */
@Service
public class AgentTaskService implements ITaskService {

    @Resource
    private IAgentRepository repository;

    @Override
    public List<AiAgentTaskScheduleVO> queryAllValidTaskSchedule() {
        return repository.queryAllValidTaskSchedule();
    }

    @Override
    public List<Long> queryAllInvalidTaskScheduleIds() {
        return repository.queryAllInvalidTaskScheduleIds();
    }
}
