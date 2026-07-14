package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanEvaluationPolicy;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanEvaluationRequest;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanEvaluationResult;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanExecutionEvaluator;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanReflectionBudget;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class PlanExecutionEvaluatorTest {

    @Test
    public void shouldAcceptCompleteResultWithSuccessfulToolEvidence() {
        ToolCall toolCall = ToolCall.builder()
                .id("call-1")
                .type("function")
                .function(ToolCall.Function.builder().name("deep_search").arguments("{}").build())
                .build();
        PlanExecutionEvaluator evaluator = new PlanExecutionEvaluator(policy(false, 75, 6000), null);

        PlanEvaluationResult result = evaluator.evaluate(new PlanEvaluationRequest(
                "调研 Spring AI",
                "搜索并整理官方资料",
                "已基于官方文档完成版本与能力整理，并保留来源证据。",
                List.of(
                        Message.fromToolCalls("需要检索", List.of(toolCall)),
                        Message.toolMessage("Spring AI official documentation, version 1.1", "call-1", null)
                ),
                AgentState.FINISHED,
                1
        ), new PlanReflectionBudget(6000));

        Assert.assertTrue(result.accepted());
        Assert.assertEquals(100, result.toolEvidenceScore());
        Assert.assertFalse(result.llmJudgeUsed());
    }

    @Test
    public void shouldRejectHardFailureEvenWhenJudgeReturnsHighScore() {
        PlanExecutionEvaluator evaluator = new PlanExecutionEvaluator(
                policy(true, 75, 6000),
                (system, user, timeout) -> """
                        {"completeness":100,"factualConsistency":100,"toolEvidence":100,"overall":100,
                         "failureReasons":[],"replanInstruction":"retry failed tool"}
                        """
        );

        PlanEvaluationResult result = evaluator.evaluate(new PlanEvaluationRequest(
                "生成报告", "获取数据", "Tooldeep_search Error. upstream timeout",
                List.of(), AgentState.ERROR, 1), new PlanReflectionBudget(6000));

        Assert.assertFalse(result.accepted());
        Assert.assertTrue(result.failureReasons().stream().anyMatch(reason -> reason.contains("error")));
        Assert.assertTrue(result.llmJudgeUsed());
    }

    @Test
    public void shouldUseJudgeFailureDimensionsForTargetedReplan() {
        PlanExecutionEvaluator evaluator = new PlanExecutionEvaluator(
                policy(true, 75, 6000),
                (system, user, timeout) -> """
                        {"completeness":45,"factualConsistency":70,"toolEvidence":30,"overall":48,
                         "failureReasons":["missing source for pricing claim"],
                         "replanInstruction":"rerun web search and cite the official pricing page"}
                        """
        );

        PlanEvaluationResult result = evaluator.evaluate(new PlanEvaluationRequest(
                "比较云服务价格", "核验价格", "价格比较已经完成，但没有附来源。",
                List.of(), AgentState.FINISHED, 2), new PlanReflectionBudget(6000));

        Assert.assertFalse(result.accepted());
        Assert.assertEquals(Integer.valueOf(48), result.llmScore());
        Assert.assertTrue(result.replanInstruction().contains("official pricing page"));
    }

    @Test
    public void shouldSkipJudgeWhenReflectionBudgetIsInsufficient() {
        AtomicInteger calls = new AtomicInteger();
        PlanExecutionEvaluator evaluator = new PlanExecutionEvaluator(
                policy(true, 75, 6000),
                (system, user, timeout) -> {
                    calls.incrementAndGet();
                    return "{}";
                }
        );

        PlanEvaluationResult result = evaluator.evaluate(new PlanEvaluationRequest(
                "简单任务", "返回完整说明", "这是一个足够完整且没有错误的任务执行结果。",
                List.of(), AgentState.FINISHED, 1), new PlanReflectionBudget(1));

        Assert.assertTrue(result.accepted());
        Assert.assertTrue(result.budgetExhausted());
        Assert.assertEquals(0, calls.get());
    }

    @Test
    public void shouldFallBackToRulesWhenJudgeFails() {
        PlanExecutionEvaluator evaluator = new PlanExecutionEvaluator(
                policy(true, 75, 6000),
                (system, user, timeout) -> {
                    throw new IllegalStateException("judge unavailable");
                }
        );

        PlanEvaluationResult result = evaluator.evaluate(new PlanEvaluationRequest(
                "简单任务", "返回完整说明", "这是一个足够完整且没有错误的任务执行结果。",
                List.of(), AgentState.FINISHED, 1), new PlanReflectionBudget(6000));

        Assert.assertTrue(result.accepted());
        Assert.assertFalse(result.llmJudgeUsed());
        Assert.assertTrue(result.estimatedTokensUsed() > 0);
    }

    @Test
    public void shouldPassCurrentDateAndToolEvidenceToJudge() {
        AtomicReference<String> judgePrompt = new AtomicReference<>();
        ToolCall toolCall = ToolCall.builder()
                .id("call-qdrant")
                .type("function")
                .function(ToolCall.Function.builder().name("deep_search").arguments("{}").build())
                .build();
        PlanExecutionEvaluator evaluator = new PlanExecutionEvaluator(
                policy(true, 75, 6000),
                (system, user, timeout) -> {
                    judgePrompt.set(user);
                    return """
                            {"completeness":100,"factualConsistency":100,"toolEvidence":100,"overall":100,
                             "failureReasons":[],"replanInstruction":""}
                            """;
                }
        );

        PlanEvaluationResult result = evaluator.evaluate(new PlanEvaluationRequest(
                "查证 Qdrant 最新能力", "搜索官方文档", "已完成查证并给出来源。",
                List.of(
                        Message.fromToolCalls("搜索官方文档", List.of(toolCall)),
                        Message.toolMessage("Qdrant official hybrid query documentation", "call-qdrant", null)
                ), AgentState.FINISHED, 1, "今天是 2026年7月13日"), new PlanReflectionBudget(6000));

        Assert.assertTrue(result.accepted());
        Assert.assertTrue(judgePrompt.get().contains("今天是 2026年7月13日"));
        Assert.assertTrue(judgePrompt.get().contains("Qdrant official hybrid query documentation"));
    }

    private PlanEvaluationPolicy policy(boolean llmJudgeEnabled, int threshold, int budget) {
        return new PlanEvaluationPolicy(
                true,
                llmJudgeEnabled,
                threshold,
                2,
                budget,
                10,
                12000,
                600,
                0d,
                "test-model"
        );
    }
}
