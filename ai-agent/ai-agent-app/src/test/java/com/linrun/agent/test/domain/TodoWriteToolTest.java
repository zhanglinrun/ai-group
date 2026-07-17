package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.model.tooloutput.TodoWriteToolOutput;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.completion.ToolExecutionEvidence;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;
import com.linrun.agent.domain.agent.runtime.work.TodoStepEvidenceScope;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TodoWriteToolTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldPersistVerifiedEvidenceRefsAndEmitThemInSnapshot() {
        Printer printer = Mockito.mock(Printer.class);
        TodoWriteTool tool = productionTool(printer, List.of(success("tool-call-search-001")));
        tool.execute(Map.of(
                "command", "create",
                "title", "调研待办",
                "steps", List.of("检索并核验资料")
        ));

        ToolResultPayload result = (ToolResultPayload) tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed",
                "step_notes", "已核验搜索结果",
                "evidence_refs", List.of("tool-call-search-001")
        ));

        TodoWriteToolOutput output = (TodoWriteToolOutput) result.getStructuredOutput();
        Assert.assertEquals(List.of("tool-call-search-001"),
                output.getAfterTodo().getEvidenceRefs().get(0));

        ArgumentCaptor<Object> snapshots = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(printer, Mockito.times(2)).send(Mockito.eq("todo_snapshot"), snapshots.capture());
        Map<String, Object> lastSnapshot = (Map<String, Object>) snapshots.getAllValues().get(1);
        List<Map<String, Object>> todos = (List<Map<String, Object>>) lastSnapshot.get("todos");
        Assert.assertEquals(List.of("tool-call-search-001"), todos.get(0).get("evidenceRefs"));
    }

    @Test
    public void shouldRejectCompletedStatusWithoutEvidenceRefsInProduction() {
        TodoWriteTool tool = productionTool(null, List.of(success("tool-call-search-001")));
        tool.execute(Map.of(
                "command", "create",
                "title", "调研待办",
                "steps", List.of("检索并核验资料")
        ));

        try {
            tool.execute(Map.of(
                    "command", "mark_step",
                    "step_index", 0,
                    "step_status", "completed"
            ));
            Assert.fail("production completion must cite successful tool evidence");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("at least one successful toolCallId"));
        }

        Assert.assertEquals(List.of("in_progress"), tool.getTodoListSnapshot().getStepStatus());
    }

    @Test
    public void shouldRejectEvidenceRefThatDoesNotPointToSuccessfulToolCall() {
        TodoWriteTool tool = productionTool(null, List.of(
                ToolExecutionEvidence.builder()
                        .toolCallId("tool-call-search-001")
                        .toolName("deep_search")
                        .success(false)
                        .errorMessage("timeout")
                        .build()
        ));
        tool.execute(Map.of(
                "command", "create",
                "title", "调研待办",
                "steps", List.of("检索并核验资料")
        ));

        try {
            tool.execute(Map.of(
                    "command", "mark_step",
                    "step_index", 0,
                    "step_status", "completed",
                    "evidence_refs", List.of("tool-call-search-001")
            ));
            Assert.fail("failed tool calls must not satisfy todo completion evidence");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("invalid refs"));
        }
    }

    @Test
    public void shouldRejectInvalidPartialEvidenceWithoutCompletingCurrentStep() {
        TodoWriteTool tool = productionTool(null, List.of(success("tool-call-search-001")),
                AgentExecutionProfile.DEEP);
        tool.execute(Map.of(
                "command", "create",
                "title", "调研待办",
                "steps", List.of("持续核验资料"),
                "evidence_policies", List.of("TOOL")
        ));

        try {
            tool.execute(Map.of(
                    "command", "mark_step",
                    "step_index", 0,
                    "step_status", "in_progress",
                    "step_notes", "只记录部分进展",
                    "evidence_refs", List.of("hallucinated-call-id")
            ));
            Assert.fail("partial Todo evidence must still reference a successful business tool call");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("current todo activation"));
        }

        Assert.assertEquals(List.of("in_progress"), tool.getTodoListSnapshot().getStepStatus());
        Assert.assertEquals(List.of(), tool.getTodoListSnapshot().getEvidenceRefs().get(0));
    }

    @Test
    public void shouldClearReconciliationOnlyWhenMarkStepConsumesPendingEvidence() {
        AgentContext context = AgentContext.builder()
                .executionProfile(AgentExecutionProfile.DEEP)
                .toolExecutionEvidence(new ArrayList<>())
                .build();
        TodoWriteTool tool = new TodoWriteTool();
        tool.setAgentContext(context);
        tool.execute(Map.of(
                "command", "create",
                "title", "严格 evidence 对账",
                "steps", List.of("核验业务结果", "继续后续工作"),
                "evidence_policies", List.of("TOOL", "TOOL")
        ));
        context.recordToolExecutionEvidence(successForCurrentStep(
                tool, "business-call-pending", false));
        Assert.assertTrue(tool.requiresEvidenceReconciliation());

        assertRejectedWhileReconciliationPending(tool, Map.of(
                "command", "create",
                "title", "错误重建",
                "steps", List.of("绕过")
        ));
        assertRejectedWhileReconciliationPending(tool, Map.of(
                "command", "update",
                "steps", List.of("绕过")
        ));
        assertRejectedWhileReconciliationPending(tool, Map.of("command", "finish"));
        assertRejectedWhileReconciliationPending(tool, Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "in_progress"
        ));
        Assert.assertTrue(tool.requiresEvidenceReconciliation());

        tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "in_progress",
                "step_notes", "显式保存部分进展",
                "evidence_refs", List.of("business-call-pending")
        ));

        Assert.assertFalse(tool.requiresEvidenceReconciliation());
        Assert.assertEquals("in_progress", tool.getTodoListSnapshot().getStepStatus().get(0));
        Assert.assertEquals(List.of("business-call-pending"),
                tool.getTodoListSnapshot().getEvidenceRefs().get(0));
    }

    @Test
    public void shouldRejectTodoWriteCallAsBusinessCompletionEvidence() {
        TodoWriteTool tool = productionTool(null, List.of(
                ToolExecutionEvidence.builder()
                        .toolCallId("tool-call-todo-create-001")
                        .toolName("todo_write")
                        .success(true)
                        .build()
        ));
        tool.execute(Map.of(
                "command", "create",
                "title", "调研待办",
                "steps", List.of("检索并核验资料")
        ));

        try {
            tool.execute(Map.of(
                    "command", "mark_step",
                    "step_index", 0,
                    "step_status", "completed",
                    "evidence_refs", List.of("tool-call-todo-create-001")
            ));
            Assert.fail("todo_write calls must not prove completion of business work");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("invalid refs"));
        }
    }

    @Test
    public void shouldRequireEvidenceRefsForToolPolicyEvenWhenNoOtherToolRan() {
        TodoWriteTool tool = productionTool(null, List.of(), AgentExecutionProfile.DEEP);
        tool.execute(Map.of(
                "command", "create",
                "title", "深度分析待办",
                "steps", List.of("形成最终结论"),
                "evidence_policies", List.of("TOOL")
        ));

        try {
            tool.execute(Map.of(
                    "command", "mark_step",
                    "step_index", 0,
                    "step_status", "completed"
            ));
            Assert.fail("TOOL completion must cite successful evidence from this activation");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("evidence_policy TOOL"));
        }
    }

    @Test
    public void shouldRequireExplicitEvidencePoliciesForNewDeepTodo() {
        TodoWriteTool tool = productionTool(null, List.of(), AgentExecutionProfile.DEEP);

        try {
            tool.execute(Map.of(
                    "command", "create",
                    "title", "缺少策略",
                    "steps", List.of("认知步骤", "工具步骤")
            ));
            Assert.fail("new DEEP todos must declare one evidence policy per step");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("evidence_policies"));
        }
    }

    @Test
    public void shouldAdvanceDeepNoneStepWithoutBusinessEvidence() {
        TodoWriteTool tool = productionTool(null, List.of(), AgentExecutionProfile.DEEP);
        tool.execute(Map.of(
                "command", "create",
                "title", "认知步骤",
                "steps", List.of("核对用户参数"),
                "evidence_policies", List.of("NONE")
        ));

        tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed",
                "step_notes", "参数已从用户输入核对"
        ));

        Assert.assertEquals(List.of("completed"), tool.getTodoListSnapshot().getStepStatus());
        Assert.assertEquals(List.of(), tool.getTodoListSnapshot().getEvidenceRefs().get(0));
    }

    @Test
    public void shouldRejectPrematureCrossStepAndNoneEvidenceInAuditedSequence() {
        AgentContext context = AgentContext.builder()
                .executionProfile(AgentExecutionProfile.DEEP)
                .toolExecutionEvidence(new ArrayList<>())
                .build();
        TodoWriteTool tool = new TodoWriteTool();
        tool.setAgentContext(context);
        tool.execute(Map.of(
                "command", "create",
                "title", "配额核验",
                "steps", List.of("核对参数", "调用配额工具", "整理结果"),
                "evidence_policies", List.of("NONE", "TOOL", "NONE")
        ));

        ToolExecutionEvidence premature = successForCurrentStep(
                tool, "quota-called-too-early", false);
        context.recordToolExecutionEvidence(premature);
        Assert.assertFalse("NONE evidence must not open the reconciliation barrier",
                tool.requiresEvidenceReconciliation());

        assertRejected(tool, Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed",
                "evidence_refs", List.of("quota-called-too-early")
        ), "NONE");
        tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed"
        ));

        assertRejected(tool, Map.of(
                "command", "mark_step",
                "step_index", 1,
                "step_status", "completed",
                "evidence_refs", List.of("quota-called-too-early")
        ), "current todo activation");

        context.recordToolExecutionEvidence(successForCurrentStep(
                tool, "quota-call-current-step", false));
        tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 1,
                "step_status", "completed",
                "evidence_refs", List.of("quota-call-current-step")
        ));

        assertRejected(tool, Map.of(
                "command", "mark_step",
                "step_index", 2,
                "step_status", "completed",
                "evidence_refs", List.of("quota-call-current-step")
        ), "NONE");
        tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 2,
                "step_status", "completed"
        ));
        Assert.assertEquals(List.of("completed", "completed", "completed"),
                tool.getTodoListSnapshot().getStepStatus());
    }

    @Test
    public void shouldRejectDuplicateAndReusedEvidenceConsumption() {
        AgentContext context = AgentContext.builder()
                .executionProfile(AgentExecutionProfile.DEEP)
                .toolExecutionEvidence(new ArrayList<>())
                .build();
        TodoWriteTool tool = new TodoWriteTool();
        tool.setAgentContext(context);
        tool.execute(Map.of(
                "command", "create",
                "title", "两步工具核验",
                "steps", List.of("第一次核验", "第二次核验"),
                "evidence_policies", List.of("TOOL", "TOOL")
        ));
        context.recordToolExecutionEvidence(successForCurrentStep(tool, "first-call", false));
        tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "in_progress",
                "evidence_refs", List.of("first-call")
        ));

        assertRejected(tool, Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed",
                "evidence_refs", List.of("first-call")
        ), "more than once");
        tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed"
        ));

        context.recordToolExecutionEvidence(successForCurrentStep(tool, "reused-call", true));
        assertRejected(tool, Map.of(
                "command", "mark_step",
                "step_index", 1,
                "step_status", "completed",
                "evidence_refs", List.of("reused-call")
        ), "non-reused");
    }

    @Test
    public void shouldAllowPureNoToolCompletionInAutoAndStandardModes() {
        for (AgentExecutionProfile profile : List.of(AgentExecutionProfile.AUTO, AgentExecutionProfile.STANDARD)) {
            TodoWriteTool tool = productionTool(null, List.of(), profile);
            tool.execute(Map.of(
                    "command", "create",
                    "title", "纯写作待办",
                    "steps", List.of("整理并输出文字")
            ));

            tool.execute(Map.of(
                    "command", "mark_step",
                    "step_index", 0,
                    "step_status", "completed"
            ));

            Assert.assertEquals(List.of("completed"), tool.getTodoListSnapshot().getStepStatus());
            Assert.assertEquals(List.of(), tool.getTodoListSnapshot().getEvidenceRefs().get(0));
        }
    }

    @Test
    public void shouldSynchronizeAgentContextTaskAfterEverySuccessfulMutation() {
        AgentContext context = AgentContext.builder()
                .task("stale-task")
                .executionProfile(AgentExecutionProfile.STANDARD)
                .build();
        TodoWriteTool tool = new TodoWriteTool();
        tool.setAgentContext(context);

        tool.execute(Map.of(
                "command", "create",
                "title", "运行态同步",
                "steps", List.of("旧步骤一", "旧步骤二")
        ));
        Assert.assertEquals("旧步骤一", context.getTask());

        tool.execute(Map.of(
                "command", "update",
                "steps", List.of("新步骤一", "新步骤二")
        ));
        Assert.assertEquals("新步骤一", context.getTask());

        tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed"
        ));
        Assert.assertEquals("新步骤二", context.getTask());

        tool.execute(Map.of(
                "command", "mark_step",
                "step_index", 1,
                "step_status", "completed"
        ));
        Assert.assertEquals("", context.getTask());

        context.setTask("stale-after-auto-finish");
        tool.execute(Map.of("command", "finish"));
        Assert.assertEquals("", context.getTask());
    }

    private TodoWriteTool productionTool(Printer printer, List<ToolExecutionEvidence> evidence) {
        return productionTool(printer, evidence, AgentExecutionProfile.STANDARD);
    }

    private TodoWriteTool productionTool(Printer printer,
                                         List<ToolExecutionEvidence> evidence,
                                         AgentExecutionProfile executionProfile) {
        TodoWriteTool tool = new TodoWriteTool();
        tool.setAgentContext(AgentContext.builder()
                .printer(printer)
                .toolExecutionEvidence(evidence)
                .executionProfile(executionProfile)
                .build());
        return tool;
    }

    private ToolExecutionEvidence success(String toolCallId) {
        return ToolExecutionEvidence.builder()
                .toolCallId(toolCallId)
                .toolName("deep_search")
                .success(true)
                .build();
    }

    private ToolExecutionEvidence successForCurrentStep(TodoWriteTool tool,
                                                        String toolCallId,
                                                        boolean reused) {
        TodoStepEvidenceScope scope = tool.getCurrentStepEvidenceScope();
        Assert.assertNotNull(scope);
        return ToolExecutionEvidence.builder()
                .toolCallId(toolCallId)
                .toolName("deep_search")
                .success(true)
                .todoStepIndex(scope.stepIndex())
                .todoStepActivationId(scope.activationId())
                .reused(reused)
                .build();
    }

    private void assertRejected(TodoWriteTool tool,
                                Map<String, Object> params,
                                String expectedMessage) {
        try {
            tool.execute(params);
            Assert.fail("todo mutation should be rejected: " + params);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }

    private void assertRejectedWhileReconciliationPending(TodoWriteTool tool,
                                                           Map<String, Object> params) {
        try {
            tool.execute(params);
            Assert.fail("pending reconciliation must reject this Todo mutation");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("pending"));
        }
        Assert.assertTrue(tool.requiresEvidenceReconciliation());
    }
}
