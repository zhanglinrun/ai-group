package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.completion.DefaultEvidenceValidator;
import com.linrun.agent.domain.agent.runtime.completion.ToolExecutionEvidence;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;
import com.linrun.agent.domain.agent.runtime.tool.common.todo.TodoLifecycleResult;
import com.linrun.agent.domain.agent.runtime.tool.common.todo.TodoLifecycleService;

import java.util.ArrayList;
import java.util.List;

/**
 * TodoLifecycleService lifecycle regression tests.
 */
public class TodoLifecycleServiceTest {

    private final TodoLifecycleService service = new TodoLifecycleService();

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyTodoListOnCreate() {
        service.create("空待办", List.of());
    }

    @Test
    public void shouldActivateFirstStepOnCreateAndAutoAdvanceWhenMarkingCompleted() {
        TodoLifecycleResult created = service.create("执行待办", List.of("步骤一", "步骤二"));

        Assert.assertEquals("步骤一", created.getCurrentStep());
        Assert.assertEquals(Integer.valueOf(0), created.getCurrentStepIndex());
        Assert.assertEquals(List.of("in_progress", "not_started"), created.getTodoList().getStepStatus());
        Assert.assertTrue(created.getAutoAdvanced());
        Assert.assertFalse(created.getAutoFinished());

        TodoLifecycleResult advanced = service.markStep(created.getTodoList(), 0, "completed", "已完成");

        Assert.assertEquals(List.of("completed", "in_progress"), advanced.getTodoList().getStepStatus());
        Assert.assertEquals(List.of("已完成", ""), advanced.getTodoList().getNotes());
        Assert.assertEquals("步骤二", advanced.getCurrentStep());
        Assert.assertEquals(Integer.valueOf(1), advanced.getCurrentStepIndex());
        Assert.assertTrue(advanced.getAutoAdvanced());
        Assert.assertFalse(advanced.getAutoFinished());
    }

    @Test
    public void shouldPreserveEvidencePoliciesAndAssignFreshActivationIds() {
        TodoLifecycleResult created = service.create(
                "带证据策略的待办",
                List.of("核对参数", "调用工具", "整理结果"),
                List.of(TodoEvidencePolicy.NONE, TodoEvidencePolicy.TOOL, TodoEvidencePolicy.NONE));

        Assert.assertEquals(
                List.of(TodoEvidencePolicy.NONE, TodoEvidencePolicy.TOOL, TodoEvidencePolicy.NONE),
                created.getTodoList().getEvidencePolicies());
        Assert.assertEquals(Long.valueOf(1L), created.getTodoList().getStepActivationIdAt(0));
        Assert.assertNull(created.getTodoList().getStepActivationIdAt(1));

        TodoLifecycleResult advanced = service.markStep(
                created.getTodoList(), 0, "completed", "参数已核对");

        Assert.assertEquals(Long.valueOf(1L), advanced.getTodoList().getStepActivationIdAt(0));
        Assert.assertEquals(Long.valueOf(2L), advanced.getTodoList().getStepActivationIdAt(1));
        Assert.assertNull(advanced.getTodoList().getStepActivationIdAt(2));
    }

    @Test
    public void shouldFreezeCompletedPrefixWhenUpdatingRemainingSteps() {
        TodoLifecycleResult created = service.create("执行待办", List.of("步骤一", "步骤二", "步骤三"));
        created.getTodoList().updateEvidenceRefs(0, List.of("tool-call-search-001"));
        TodoLifecycleResult advanced = service.markStep(created.getTodoList(), 0, "completed", "首步完成");

        TodoLifecycleResult updated = service.update(advanced.getTodoList(), "重排后的待办", List.of("新步骤A", "新步骤B"));

        Assert.assertEquals("重排后的待办", updated.getTodoList().getTitle());
        Assert.assertEquals(List.of("步骤一", "新步骤A", "新步骤B"), updated.getTodoList().getSteps());
        Assert.assertEquals(List.of("completed", "in_progress", "not_started"), updated.getTodoList().getStepStatus());
        Assert.assertEquals(List.of("首步完成", "", ""), updated.getTodoList().getNotes());
        Assert.assertEquals(List.of(
                        List.of("tool-call-search-001"), List.of(), List.of()),
                updated.getTodoList().getEvidenceRefs());
        Assert.assertEquals("新步骤A", updated.getCurrentStep());
        Assert.assertTrue(updated.getAutoAdvanced());
        Assert.assertFalse(updated.getAutoFinished());
    }

    @Test
    public void shouldUseFreshActivationWhenReplacingUnfinishedSuffix() {
        TodoLifecycleResult created = service.create(
                "证据隔离待办",
                List.of("准备", "旧工具步骤"),
                List.of(TodoEvidencePolicy.NONE, TodoEvidencePolicy.TOOL));
        TodoLifecycleResult advanced = service.markStep(
                created.getTodoList(), 0, "completed", "准备完成");
        Long oldActivationId = advanced.getTodoList().getStepActivationIdAt(1);
        ToolExecutionEvidence oldEvidence = ToolExecutionEvidence.builder()
                .toolCallId("old-tool-call")
                .toolName("quota_tool")
                .operationKey("old-operation")
                .success(true)
                .todoStepIndex(1)
                .todoStepActivationId(oldActivationId)
                .build();

        TodoLifecycleResult updated = service.update(
                advanced.getTodoList(),
                null,
                List.of("替换后的工具步骤"),
                List.of(TodoEvidencePolicy.TOOL));
        Long newActivationId = updated.getTodoList().getStepActivationIdAt(1);

        Assert.assertNotEquals(oldActivationId, newActivationId);
        Assert.assertTrue(newActivationId > oldActivationId);

        DefaultEvidenceValidator validator = new DefaultEvidenceValidator();
        Assert.assertFalse(validator.isSuccessfulBusinessEvidenceForStep(
                "old-tool-call", List.of(oldEvidence), 1, newActivationId));

        ToolExecutionEvidence newEvidence = ToolExecutionEvidence.builder()
                .toolCallId("new-tool-call")
                .toolName("quota_tool")
                .operationKey("new-operation")
                .success(true)
                .todoStepIndex(1)
                .todoStepActivationId(newActivationId)
                .build();
        Assert.assertTrue(validator.isSuccessfulBusinessEvidenceForStep(
                "new-tool-call", List.of(newEvidence), 1, newActivationId));
    }

    @Test
    public void shouldRepairMissingCurrentStepOrFailFast() {
        TodoList repairable = TodoList.builder()
                .title("repairable")
                .steps(new ArrayList<>(List.of("已完成步骤", "待执行步骤")))
                .stepStatus(new ArrayList<>(List.of("completed", "not_started")))
                .notes(new ArrayList<>(List.of("", "")))
                .build();

        TodoLifecycleResult repaired = service.ensureExecutable(repairable);

        Assert.assertEquals("待执行步骤", repaired.getCurrentStep());
        Assert.assertEquals(Integer.valueOf(1), repaired.getCurrentStepIndex());
        Assert.assertEquals(List.of("completed", "in_progress"), repaired.getTodoList().getStepStatus());
        Assert.assertTrue(repaired.getAutoAdvanced());

        TodoList broken = TodoList.builder()
                .title("broken")
                .steps(new ArrayList<>(List.of("步骤一", "步骤二")))
                .stepStatus(new ArrayList<>(List.of("completed", "blocked")))
                .notes(new ArrayList<>(List.of("", "")))
                .build();

        try {
            service.ensureExecutable(broken);
            Assert.fail("应当在缺失当前步骤且无法修复时快速失败");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("current todo item"));
        }
    }

    @Test
    public void shouldAutoFinishWhenFinalStepCompleted() {
        TodoLifecycleResult created = service.create("收口待办", List.of("最后一步"));

        TodoLifecycleResult finished = service.markStep(created.getTodoList(), 0, "completed", "全部完成");

        Assert.assertTrue(finished.getAutoFinished());
        Assert.assertFalse(finished.getAutoAdvanced());
        Assert.assertEquals(List.of("completed"), finished.getTodoList().getStepStatus());
        Assert.assertTrue(service.isAllStepsCompleted(finished.getTodoList()));
        Assert.assertEquals("", finished.getCurrentStep());
        Assert.assertNull(finished.getCurrentStepIndex());
    }

    @Test
    public void shouldRejectFinishWithoutBulkCompletingPendingTodos() {
        TodoLifecycleResult created = service.create("不可跳过", List.of("步骤一", "步骤二"));

        try {
            service.finish(created.getTodoList());
            Assert.fail("finish must not complete untouched steps");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("cannot finish"));
        }

        Assert.assertEquals(List.of("in_progress", "not_started"), created.getTodoList().getStepStatus());
    }

    @Test
    public void shouldKeepOnlyOneInProgressStep() {
        TodoLifecycleResult created = service.create("单活步骤", List.of("步骤一", "步骤二", "步骤三"));

        try {
            service.markStep(created.getTodoList(), 1, "in_progress", "非法并行激活");
            Assert.fail("a future step must not become in_progress while another step is active");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("only one"));
        }

        Assert.assertEquals(List.of("in_progress", "not_started", "not_started"),
                created.getTodoList().getStepStatus());
        Assert.assertEquals(1L, created.getTodoList().getStepStatus().stream()
                .filter("in_progress"::equals)
                .count());
    }
}
