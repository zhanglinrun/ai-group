package com.linrun.agent.trigger.job;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.service.dispatch.IAgentDispatchService;
import com.linrun.agent.trigger.stream.AgentSessionPrinter;
import com.linrun.agent.trigger.stream.HeadlessAgentSessionStream;
import com.linrun.agent.domain.agent.model.valobj.AiAgentTaskScheduleVO;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.enums.AgentType;
import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * 将定时调度配置转换为 AgentRequest 并同步分发。
 * 系统内部任务：不绑定用户身份、不冻结配额。
 */
@Slf4j
@Service
public class ScheduledAgentTaskExecutor {

    private final IAgentDispatchService agentDispatchService;
    private final AgentStreamEventStore streamEventStore;

    public ScheduledAgentTaskExecutor(IAgentDispatchService agentDispatchService) {
        this(agentDispatchService, (AgentStreamEventStore) null);
    }

    @Autowired
    public ScheduledAgentTaskExecutor(IAgentDispatchService agentDispatchService,
                                      ObjectProvider<AgentStreamEventStore> streamEventStore) {
        this(agentDispatchService, streamEventStore.getIfAvailable());
    }

    private ScheduledAgentTaskExecutor(IAgentDispatchService agentDispatchService,
                                       AgentStreamEventStore streamEventStore) {
        this.agentDispatchService = agentDispatchService;
        this.streamEventStore = streamEventStore;
    }

    /**
     * 校验调度记录是否可注册。
     */
    public boolean isSchedulable(AiAgentTaskScheduleVO schedule) {
        if (schedule == null) {
            return false;
        }
        if (schedule.getId() == null) {
            return false;
        }
        if (StringUtils.isBlank(schedule.getAgentId())) {
            return false;
        }
        if (StringUtils.isBlank(schedule.getCronExpression())) {
            return false;
        }
        return StringUtils.isNotBlank(schedule.getTaskParam());
    }

    /**
     * 构建一次触发的请求（每次调用生成新的 request/session ID）。
     */
    public AgentRequest buildRequest(AiAgentTaskScheduleVO schedule, String taskParam) {
        String runId = newRunId(schedule.getId());
        return AgentRequest.builder()
                .requestId(runId)
                .sessionId(runId)
                .query(taskParam)
                .aiAgentId(schedule.getAgentId())
                // Scheduled runs use the same Claude Code-style unified Harness as Web runs.
                .agentType(AgentType.AGENT_LOOP.getValue())
                .isStream(false)
                .build();
    }

    /**
     * 同步执行并关闭流；失败时 completeWithError 后重新抛出。
     */
    public void execute(AiAgentTaskScheduleVO schedule, String taskParam) {
        AgentRequest request = buildRequest(schedule, taskParam);
        HeadlessAgentSessionStream stream = new HeadlessAgentSessionStream();
        executeAndClose(request, stream);
    }

    public void executeAndClose(AgentRequest request, AgentMessageStream stream) {
        try {
            agentDispatchService.dispatch(request,
                    new AgentSessionPrinter(stream, request, streamEventStore));
            stream.complete();
        } catch (Exception ex) {
            stream.completeWithError(ex);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("scheduled agent task failed requestId=" + request.getRequestId(), ex);
        }
    }

    static String newRunId(Long taskId) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String prefix = "task-" + (taskId == null ? "0" : taskId) + "-";
        // ledger requestId 常见 varchar(64)
        int maxUuid = Math.max(8, 64 - prefix.length());
        if (uuid.length() > maxUuid) {
            uuid = uuid.substring(0, maxUuid);
        }
        return prefix + uuid;
    }
}
