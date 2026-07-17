package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.linrun.agent.types.job.model.TaskScheduleVO;
import com.linrun.agent.types.job.provider.ITaskDataProvider;
import com.linrun.agent.types.job.service.TaskJobService;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TaskJobService 对缺失执行器的防御回归。
 */
public class TaskJobServiceTest {

    @Test
    public void shouldRejectTaskWithoutExecutor() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        try {
            TaskJobService service = new TaskJobService(scheduler, List.<ITaskDataProvider>of());
            TaskScheduleVO task = new TaskScheduleVO();
            task.setId(7L);
            task.setDescription("no-executor");
            task.setCronExpression("0 0 0 1 1 ? 2099");

            Method method = TaskJobService.class.getDeclaredMethod("executeTaskWithFunction", TaskScheduleVO.class);
            method.setAccessible(true);
            method.invoke(service, task);
            // 方法内部吞掉异常并打日志，不应抛出到调用方；关键是不要 NPE 崩进程。
            Assert.assertNull(task.getTaskExecutor());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void shouldRunTaskWhenExecutorPresent() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        try {
            TaskJobService service = new TaskJobService(scheduler, List.<ITaskDataProvider>of());
            AtomicBoolean ran = new AtomicBoolean(false);
            TaskScheduleVO task = new TaskScheduleVO();
            task.setId(8L);
            task.setDescription("ok");
            task.setCronExpression("0 0 0 1 1 ? 2099");
            task.setTaskLogic(() -> ran.set(true));

            Method method = TaskJobService.class.getDeclaredMethod("executeTaskWithFunction", TaskScheduleVO.class);
            method.setAccessible(true);
            method.invoke(service, task);
            Assert.assertTrue(ran.get());
        } finally {
            scheduler.shutdown();
        }
    }
}
