package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.completion.CompletionDecision;
import com.linrun.agent.domain.agent.runtime.completion.CompletionRequest;
import com.linrun.agent.domain.agent.runtime.completion.DefaultCompletionGate;
import com.linrun.agent.domain.agent.runtime.completion.DeterministicFinalVerifier;
import com.linrun.agent.domain.agent.runtime.completion.ToolExecutionEvidence;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultCompletionGateTest {

    private final DefaultCompletionGate gate = new DefaultCompletionGate(null);

    @Test
    public void shouldRejectDeepCompletionWithoutTodoList() {
        CompletionDecision decision = gate.evaluate(request(null, List.of(), false, false));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().contains("Deep execution requires a todo list."));
    }

    @Test
    public void shouldRejectDeepCompletionWithPendingTodo() {
        TodoList todoList = todoList(List.of("completed", "in_progress"));

        CompletionDecision decision = gate.evaluate(request(todoList, List.of(), false, false));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().contains("The todo list is not fully completed."));
    }

    @Test
    public void shouldAllowDeepCompletionWhenEveryTodoIsCompleted() {
        ToolExecutionEvidence evidence = toolEvidence("call-verified-1", "web_fetch", true);
        CompletionDecision decision = gate.evaluate(request(
                todoList(List.of("completed", "completed"), "call-verified-1"),
                List.of(evidence), false, false));

        Assert.assertTrue(decision.isCanStop());
    }

    @Test
    public void shouldAllowMixedNoneAndToolPoliciesWithScopedEvidence() {
        TodoList todoList = strictTodo(
                List.of(TodoEvidencePolicy.NONE, TodoEvidencePolicy.TOOL, TodoEvidencePolicy.NONE),
                List.of(List.of(), List.of("call-tool-step"), List.of()));
        ToolExecutionEvidence evidence = scopedToolEvidence(
                "call-tool-step", "quota_tool", 1, 2L, false);

        CompletionDecision decision = gate.evaluate(request(
                todoList, List.of(evidence), false, false));

        Assert.assertTrue(decision.getReasons().toString(), decision.isCanStop());
    }

    @Test
    public void shouldRejectCrossStepOrReusedEvidenceForToolPolicy() {
        TodoList todoList = strictTodo(
                List.of(TodoEvidencePolicy.NONE, TodoEvidencePolicy.TOOL),
                List.of(List.of(), List.of("call-wrong-scope")));

        CompletionDecision crossStep = gate.evaluate(request(
                todoList,
                List.of(scopedToolEvidence(
                        "call-wrong-scope", "quota_tool", 0, 1L, false)),
                false,
                false));
        CompletionDecision reused = gate.evaluate(request(
                strictTodo(
                        List.of(TodoEvidencePolicy.NONE, TodoEvidencePolicy.TOOL),
                        List.of(List.of(), List.of("call-reused"))),
                List.of(scopedToolEvidence(
                        "call-reused", "quota_tool", 1, 2L, true)),
                false,
                false));

        Assert.assertFalse(crossStep.isCanStop());
        Assert.assertFalse(reused.isCanStop());
        Assert.assertTrue(crossStep.getReasons().contains(
                "Completed todo items are missing verified tool evidence."));
        Assert.assertTrue(reused.getReasons().contains(
                "Completed todo items are missing verified tool evidence."));
    }

    @Test
    public void shouldRejectBusinessCallExecutedDuringNonePolicyStep() {
        TodoList todoList = strictTodo(
                List.of(TodoEvidencePolicy.NONE),
                List.of(List.of()));

        CompletionDecision decision = gate.evaluate(request(
                todoList,
                List.of(scopedToolEvidence(
                        "call-forbidden-on-none", "quota_tool", 0, 1L, false)),
                false,
                false));

        Assert.assertFalse(decision.isCanStop());
    }

    @Test
    public void shouldRejectDeepCompletionWhenCompletedTodoHasNoTypedEvidence() {
        CompletionDecision decision = gate.evaluate(request(
                todoList(List.of("completed")), List.of(), false, false));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().contains(
                "Completed todo items are missing verified tool evidence."));
    }

    @Test
    public void shouldRejectCompletionAfterUnresolvedToolFailure() {
        ToolExecutionEvidence failure = ToolExecutionEvidence.builder()
                .toolCallId("call-1")
                .toolName("web_fetch")
                .success(false)
                .errorMessage("connection refused")
                .build();

        CompletionDecision decision = gate.evaluate(request(
                todoList(List.of("completed")), List.of(failure), false, false));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream().anyMatch(reason -> reason.contains("web_fetch")));
    }

    @Test
    public void shouldNotHideEarlierFailureWithUnrelatedLaterSuccess() {
        List<ToolExecutionEvidence> evidence = List.of(
                toolEvidence("call-search-1", "web_fetch", false),
                toolEvidence("call-todo-1", "todo_write", true));

        CompletionDecision decision = gate.evaluate(request(
                todoList(List.of("completed")), evidence, false, false));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream().anyMatch(reason -> reason.contains("web_fetch")));
    }

    @Test
    public void shouldResolveFailureAfterSuccessfulRetryOfSameTool() {
        List<ToolExecutionEvidence> evidence = List.of(
                toolEvidence("call-search-1", "web_fetch", "web_fetch:product-a", false),
                toolEvidence("call-search-2", "web_fetch", "web_fetch:product-a", true));

        CompletionDecision decision = gate.evaluate(request(
                todoList(List.of("completed"), "call-search-2"), evidence, false, false));

        Assert.assertTrue(decision.isCanStop());
    }

    @Test
    public void shouldNotHideFailedOperationWithDifferentSuccessfulInput() {
        List<ToolExecutionEvidence> evidence = List.of(
                toolEvidence("call-search-a", "web_fetch", "web_fetch:product-a", false),
                toolEvidence("call-search-b", "web_fetch", "web_fetch:product-b", true));

        CompletionDecision decision = gate.evaluate(request(
                todoList(List.of("completed"), "call-search-b"), evidence, false, false));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream().anyMatch(reason -> reason.contains("web_fetch")));
    }

    @Test
    public void shouldResolveCorrectableInputFailureAfterSuccessfulCorrectedCall() {
        List<ToolExecutionEvidence> evidence = List.of(
                ToolExecutionEvidence.builder()
                        .toolCallId("call-invalid")
                        .toolName("web_fetch")
                        .operationKey("web_fetch:invalid-input")
                        .success(false)
                        .correctableInputFailure(true)
                        .build(),
                toolEvidence("call-corrected", "web_fetch", "web_fetch:corrected-input", true));

        CompletionDecision decision = gate.evaluate(request(
                todoList(List.of("completed"), "call-corrected"), evidence, false, false));

        Assert.assertTrue(decision.getReasons().toString(), decision.isCanStop());
    }

    @Test
    public void shouldNotResolveCorrectableInputFailureFromDifferentTodoActivation() {
        List<ToolExecutionEvidence> evidence = List.of(
                ToolExecutionEvidence.builder()
                        .toolCallId("call-invalid")
                        .toolName("web_fetch")
                        .operationKey("web_fetch:invalid-input")
                        .success(false)
                        .correctableInputFailure(true)
                        .todoStepIndex(0)
                        .todoStepActivationId(1L)
                        .build(),
                scopedToolEvidence("call-other-step", "web_fetch", 1, 2L, false));

        CompletionDecision decision = gate.evaluate(request(
                todoList(List.of("completed"), "call-other-step"), evidence, false, false));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream().anyMatch(reason -> reason.contains("web_fetch")));
    }

    @Test
    public void shouldRunVerifierForAutoModeWhenHarnessOpenedTodoList() {
        AtomicBoolean verified = new AtomicBoolean();
        DefaultCompletionGate autoGate = new DefaultCompletionGate(request -> {
            verified.set(true);
            return CompletionDecision.allow(true);
        });

        CompletionDecision decision = autoGate.evaluate(request(
                AgentExecutionProfile.AUTO,
                todoList(List.of("completed")),
                List.of(),
                false,
                false));

        Assert.assertTrue(decision.isCanStop());
        Assert.assertTrue(verified.get());
        Assert.assertTrue(decision.isVerifierExecuted());
    }

    @Test
    public void shouldRunDeterministicVerifierForStandardComparisonWithoutTodo() {
        DefaultCompletionGate comparisonGate = new DefaultCompletionGate(new DeterministicFinalVerifier());

        CompletionDecision decision = comparisonGate.evaluate(comparisonRequest(
                AgentExecutionProfile.STANDARD,
                "Codex、Claude Code、Cursor；价格和能力。"));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("subject-by-dimension")));
    }

    @Test
    public void shouldRunDeterministicVerifierForAutoComparisonWithoutTodo() {
        DefaultCompletionGate comparisonGate = new DefaultCompletionGate(new DeterministicFinalVerifier());

        CompletionDecision decision = comparisonGate.evaluate(comparisonRequest(
                AgentExecutionProfile.AUTO,
                "Codex、Claude Code、Cursor；价格和能力。"));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("subject-by-dimension")));
    }

    @Test
    public void shouldAllowStandardNonComparisonWithoutTodoAfterDeterministicVerification() {
        DefaultCompletionGate standardGate = new DefaultCompletionGate(new DeterministicFinalVerifier());
        CompletionDecision decision = standardGate.evaluate(CompletionRequest.builder()
                .goal("解释 Java volatile 的可见性")
                .draftAnswer("volatile 写与随后读之间建立 happens-before 关系。")
                .executionProfile(AgentExecutionProfile.STANDARD)
                .build());

        Assert.assertTrue(decision.isCanStop());
        Assert.assertTrue(decision.isVerifierExecuted());
    }

    @Test
    public void shouldRejectCompletionWhenRequiredReportArtifactIsMissing() {
        CompletionDecision decision = gate.evaluate(request(
                todoList(List.of("completed")), List.of(), true, false));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().contains("The requested report artifact was not produced."));
    }

    @Test
    public void shouldRejectExplicitNetworkRequirementWithoutSuccessfulNetworkEvidence() {
        CompletionDecision decision = gate.evaluate(networkRequest(List.of()));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("network lookup")));
    }

    @Test
    public void shouldNotTreatUnrelatedSuccessfulToolAsNetworkEvidence() {
        CompletionDecision decision = gate.evaluate(networkRequest(List.of(
                toolEvidence("call-report", "report_tool", true))));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("network lookup")));
    }

    @Test
    public void shouldAllowExplicitNetworkRequirementAfterSuccessfulNetworkLookup() {
        CompletionDecision decision = gate.evaluate(networkRequest(List.of(
                toolEvidence("call-search", "deep_search", true))));

        Assert.assertTrue(decision.isCanStop());
    }

    @Test
    public void shouldRejectCompletionWhenExplicitlyRequiredToolWasNotUsed() {
        CompletionDecision decision = gate.evaluate(CompletionRequest.builder()
                .goal("必须调用 utility_estimate_llm_quota 工具")
                .draftAnswer("已经用其他工具算出了结果。")
                .executionProfile(AgentExecutionProfile.STANDARD)
                .toolEvidence(List.of(toolEvidence("call-code", "code_interpreter", true)))
                .requiredToolName("mcp__utility__utility_estimate_llm_quota")
                .build());

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("mcp__utility__utility_estimate_llm_quota")));
    }

    @Test
    public void shouldAllowCompletionAfterExplicitlyRequiredToolSucceeds() {
        CompletionDecision decision = gate.evaluate(CompletionRequest.builder()
                .goal("必须调用 utility_estimate_llm_quota 工具")
                .draftAnswer("指定工具已成功调用。")
                .executionProfile(AgentExecutionProfile.STANDARD)
                .toolEvidence(List.of(toolEvidence(
                        "call-required", "mcp__utility__utility_estimate_llm_quota", true)))
                .requiredToolName("mcp__utility__utility_estimate_llm_quota")
                .build());

        Assert.assertTrue(decision.isCanStop());
    }

    @Test
    public void shouldNotAcceptRawAliasEvidenceForCanonicalRequiredTool() {
        CompletionDecision decision = gate.evaluate(CompletionRequest.builder()
                .goal("必须调用 utility_estimate_llm_quota 工具")
                .draftAnswer("工具名称没有经过 dispatcher canonicalization。")
                .executionProfile(AgentExecutionProfile.STANDARD)
                .toolEvidence(List.of(toolEvidence(
                        "call-raw-alias", "utility_estimate_llm_quota", true)))
                .requiredToolName("mcp__utility__utility_estimate_llm_quota")
                .build());

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("mcp__utility__utility_estimate_llm_quota")));
    }

    @Test
    public void shouldRejectCompletionWhenRequiredAndForbiddenToolsBothSucceeded() {
        ToolInvocationContract contract = new ToolInvocationContract(
                Set.of("required_tool"),
                Set.of("required_tool"),
                Set.of("blocked_tool"),
                true);
        CompletionDecision decision = gate.evaluate(CompletionRequest.builder()
                .goal("只能调用 required_tool，禁止使用 blocked_tool")
                .draftAnswer("required tool result")
                .executionProfile(AgentExecutionProfile.STANDARD)
                .toolEvidence(List.of(
                        toolEvidence("call-required", "required_tool", true),
                        toolEvidence("call-blocked", "blocked_tool", true)))
                .requiredToolName("required_tool")
                .toolInvocationContract(contract)
                .build());

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("blocked_tool")
                        && reason.contains("invocation contract")));
    }

    @Test
    public void shouldAllowTodoControlEvidenceInsideExclusiveContract() {
        ToolInvocationContract contract = new ToolInvocationContract(
                Set.of("required_tool"),
                Set.of("required_tool"),
                Set.of("blocked_tool"),
                true);
        CompletionDecision decision = gate.evaluate(CompletionRequest.builder()
                .goal("只能调用 required_tool")
                .draftAnswer("required tool result")
                .executionProfile(AgentExecutionProfile.STANDARD)
                .toolEvidence(List.of(
                        toolEvidence("call-todo", "todo_write", true),
                        toolEvidence("call-required", "required_tool", true)))
                .requiredToolName("required_tool")
                .toolInvocationContract(contract)
                .build());

        Assert.assertTrue(decision.isCanStop());
    }

    private CompletionRequest request(TodoList todoList,
                                      List<ToolExecutionEvidence> evidence,
                                      boolean reportRequired,
                                      boolean reportPresent) {
        return request(AgentExecutionProfile.DEEP, todoList, evidence, reportRequired, reportPresent);
    }

    private CompletionRequest request(AgentExecutionProfile profile,
                                      TodoList todoList,
                                      List<ToolExecutionEvidence> evidence,
                                      boolean reportRequired,
                                      boolean reportPresent) {
        return CompletionRequest.builder()
                .goal("完成完整任务")
                .draftAnswer("这是最终答案")
                .executionProfile(profile)
                .todoList(todoList)
                .toolEvidence(evidence)
                .reportArtifactRequired(reportRequired)
                .reportArtifactPresent(reportPresent)
                .build();
    }

    private CompletionRequest comparisonRequest(AgentExecutionProfile profile, String draftAnswer) {
        return CompletionRequest.builder()
                .goal("对比 Codex、Claude Code、Cursor 的价格和能力")
                .draftAnswer(draftAnswer)
                .executionProfile(profile)
                .build();
    }

    private CompletionRequest networkRequest(List<ToolExecutionEvidence> evidence) {
        return CompletionRequest.builder()
                .goal("请联网搜索并查证最新官方价格")
                .draftAnswer("这是基于检索结果整理的答案。")
                .executionProfile(AgentExecutionProfile.STANDARD)
                .toolEvidence(evidence)
                .networkLookupRequired(true)
                .build();
    }

    private ToolExecutionEvidence toolEvidence(String callId, String toolName, boolean success) {
        return toolEvidence(callId, toolName, null, success);
    }

    private ToolExecutionEvidence toolEvidence(String callId,
                                                String toolName,
                                                String operationKey,
                                                boolean success) {
        return ToolExecutionEvidence.builder()
                .toolCallId(callId)
                .toolName(toolName)
                .operationKey(operationKey)
                .success(success)
                .errorMessage(success ? null : "failed")
                .build();
    }

    private ToolExecutionEvidence scopedToolEvidence(String callId,
                                                      String toolName,
                                                      int stepIndex,
                                                      long activationId,
                                                      boolean reused) {
        return ToolExecutionEvidence.builder()
                .toolCallId(callId)
                .toolName(toolName)
                .operationKey(toolName + ":operation")
                .success(true)
                .todoStepIndex(stepIndex)
                .todoStepActivationId(activationId)
                .reused(reused)
                .build();
    }

    private TodoList todoList(List<String> statuses) {
        return todoList(statuses, null);
    }

    private TodoList todoList(List<String> statuses, String evidenceRef) {
        List<String> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<List<String>> evidenceRefs = new ArrayList<>();
        for (int index = 0; index < statuses.size(); index++) {
            steps.add("步骤" + (index + 1));
            notes.add("");
            evidenceRefs.add("completed".equals(statuses.get(index)) && evidenceRef != null
                    ? List.of(evidenceRef)
                    : List.of());
        }
        return TodoList.builder()
                .title("测试待办")
                .steps(steps)
                .stepStatus(new ArrayList<>(statuses))
                .notes(notes)
                .evidenceRefs(evidenceRefs)
                .build();
    }

    private TodoList strictTodo(List<TodoEvidencePolicy> policies,
                                List<List<String>> evidenceRefs) {
        List<String> steps = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<Long> activationIds = new ArrayList<>();
        for (int index = 0; index < policies.size(); index++) {
            steps.add("步骤" + (index + 1));
            statuses.add("completed");
            notes.add("");
            activationIds.add((long) index + 1L);
        }
        return TodoList.builder()
                .title("严格证据待办")
                .steps(steps)
                .stepStatus(statuses)
                .notes(notes)
                .evidenceRefs(new ArrayList<>(evidenceRefs))
                .evidencePolicies(new ArrayList<>(policies))
                .stepActivationIds(activationIds)
                .build();
    }
}
