package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.work.TaskGraphService;
import com.linrun.agent.domain.agent.work.WorkTask;

import java.util.List;
import java.util.Map;

public class TaskGraphServiceTest {

    @Test
    public void shouldUnlockDependentTaskOnlyAfterBlockerCompletes() {
        TaskGraphService service = new TaskGraphService();
        WorkTask research = service.create("u1", "w1", "调研", "", "正在调研", List.of(), Map.of());
        WorkTask report = service.create("u1", "w1", "报告", "", "正在写报告", List.of(research.id()), Map.of());

        Assert.assertEquals(List.of(research.id()), service.ready("u1", "w1").stream().map(WorkTask::id).toList());
        Assert.assertThrows(IllegalStateException.class,
                () -> service.claim("u1", "w1", report.id(), "agent-a"));
        Assert.assertThrows(IllegalStateException.class,
                () -> service.updateStatus("u1", "w1", report.id(), WorkTask.Status.COMPLETED, "agent-a", null));

        service.claim("u1", "w1", research.id(), "agent-a");
        service.updateStatus("u1", "w1", research.id(), WorkTask.Status.COMPLETED, "agent-a", "evidence-ok");

        WorkTask unlocked = service.get("u1", "w1", report.id());
        Assert.assertTrue(unlocked.blockedBy().isEmpty());
        Assert.assertTrue(unlocked.isUnblocked());
    }

    @Test
    public void shouldRejectCrossOwnerAccessAndDuplicateClaim() {
        TaskGraphService service = new TaskGraphService();
        WorkTask task = service.create("u1", "w1", "实现", "", "正在实现", List.of(), Map.of());
        service.claim("u1", "w1", task.id(), "agent-a");

        Assert.assertThrows(IllegalArgumentException.class, () -> service.get("u2", "w1", task.id()));
        Assert.assertThrows(IllegalStateException.class,
                () -> service.claim("u1", "w1", task.id(), "agent-b"));
    }
}
