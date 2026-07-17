package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.service.dispatch.IAgentDispatchService;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.trigger.stream.HeadlessAgentSessionStream;
import com.linrun.agent.domain.agent.service.task.ITaskService;
import com.linrun.agent.trigger.job.ScheduledAgentTaskExecutor;
import com.linrun.agent.domain.agent.model.valobj.AiAgentTaskScheduleVO;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.enums.AgentType;
import com.linrun.agent.trigger.job.AgentTaskJob;
import com.linrun.agent.types.job.model.TaskScheduleVO;

import java.util.List;

/**
 * 定时任务执行契约回归：映射、分发、失败传播与坏记录隔离。
 */
public class AgentTaskJobTest {

    @Test
    public void shouldMapScheduleAndInstallExecutor() throws Exception {
        ITaskService taskService = Mockito.mock(ITaskService.class);
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        ScheduledAgentTaskExecutor executor = new ScheduledAgentTaskExecutor(dispatchService);
        AgentTaskJob job = new AgentTaskJob(taskService, executor);

        AiAgentTaskScheduleVO schedule = AiAgentTaskScheduleVO.builder()
                .id(11L)
                .agentId("dev_role_001")
                .description("daily")
                .cronExpression("0 0 9 * * ?")
                .taskParam("生成日报")
                .build();
        Mockito.when(taskService.queryAllValidTaskSchedule()).thenReturn(List.of(schedule));

        List<TaskScheduleVO> mapped = job.queryAllValidTaskSchedule();
        Assert.assertEquals(1, mapped.size());
        TaskScheduleVO vo = mapped.get(0);
        Assert.assertEquals(Long.valueOf(11L), vo.getId());
        Assert.assertEquals("0 0 9 * * ?", vo.getCronExpression());
        Assert.assertEquals("daily", vo.getDescription());
        Assert.assertEquals("生成日报", vo.getTaskParam());
        Assert.assertNotNull(vo.getTaskExecutor());

        vo.getTaskExecutor().get().run();
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        Mockito.verify(dispatchService, Mockito.times(1)).dispatch(requestCaptor.capture(), Mockito.any());
        AgentRequest request = requestCaptor.getValue();
        Assert.assertEquals("dev_role_001", request.getAiAgentId());
        Assert.assertEquals("生成日报", request.getQuery());
        Assert.assertEquals(AgentType.AGENT_LOOP.getValue(), request.getAgentType());
        Assert.assertEquals(Boolean.FALSE, request.getIsStream());
        Assert.assertNotNull(request.getRequestId());
        Assert.assertNotNull(request.getSessionId());
    }

    @Test
    public void shouldGenerateFreshRequestAndSessionIdsForEveryFire() throws Exception {
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        ScheduledAgentTaskExecutor executor = new ScheduledAgentTaskExecutor(dispatchService);
        AiAgentTaskScheduleVO schedule = AiAgentTaskScheduleVO.builder()
                .id(22L)
                .agentId("dev_role_001")
                .cronExpression("0/5 * * * * ?")
                .taskParam("重复执行")
                .build();

        executor.execute(schedule, schedule.getTaskParam());
        executor.execute(schedule, schedule.getTaskParam());

        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        Mockito.verify(dispatchService, Mockito.times(2)).dispatch(requestCaptor.capture(), Mockito.any());
        List<AgentRequest> requests = requestCaptor.getAllValues();
        Assert.assertNotEquals(requests.get(0).getRequestId(), requests.get(1).getRequestId());
        Assert.assertNotEquals(requests.get(0).getSessionId(), requests.get(1).getSessionId());
        Assert.assertEquals(requests.get(0).getRequestId(), requests.get(0).getSessionId());
    }

    @Test
    public void shouldPropagateDispatchFailureToCaller() throws Exception {
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        Mockito.doThrow(new IllegalStateException("dispatch failed"))
                .when(dispatchService).dispatch(Mockito.any(), Mockito.any());
        ScheduledAgentTaskExecutor executor = new ScheduledAgentTaskExecutor(dispatchService);
        HeadlessAgentSessionStream stream = new HeadlessAgentSessionStream();
        AgentRequest request = AgentRequest.builder()
                .requestId("task-1-abc")
                .sessionId("task-1-abc")
                .query("x")
                .aiAgentId("dev_role_001")
                .agentType(AgentType.AGENT_LOOP.getValue())
                .isStream(false)
                .build();

        try {
            executor.executeAndClose(request, stream);
            Assert.fail("应向上抛出 dispatch 异常");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("dispatch failed", expected.getMessage());
        }
        Assert.assertTrue(stream.isCompletedWithError());
        Assert.assertFalse(stream.isCompleted());
    }

    @Test
    public void shouldSkipInvalidScheduleWithoutBlockingValidSchedules() {
        ITaskService taskService = Mockito.mock(ITaskService.class);
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        ScheduledAgentTaskExecutor executor = new ScheduledAgentTaskExecutor(dispatchService);
        AgentTaskJob job = new AgentTaskJob(taskService, executor);

        AiAgentTaskScheduleVO invalid = AiAgentTaskScheduleVO.builder()
                .id(1L)
                .agentId("")
                .cronExpression("0 0 * * * ?")
                .taskParam("bad")
                .build();
        AiAgentTaskScheduleVO valid = AiAgentTaskScheduleVO.builder()
                .id(2L)
                .agentId("dev_role_001")
                .cronExpression("0 0 * * * ?")
                .taskParam("ok")
                .build();
        Mockito.when(taskService.queryAllValidTaskSchedule()).thenReturn(List.of(invalid, valid));

        List<TaskScheduleVO> mapped = job.queryAllValidTaskSchedule();
        Assert.assertEquals(1, mapped.size());
        Assert.assertEquals(Long.valueOf(2L), mapped.get(0).getId());
        Assert.assertNotNull(mapped.get(0).getTaskExecutor());
    }
}
