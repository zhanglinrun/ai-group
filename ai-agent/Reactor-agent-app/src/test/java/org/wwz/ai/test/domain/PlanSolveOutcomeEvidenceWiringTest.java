package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanEvaluationPolicy;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanEvaluationRequest;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanEvaluationResult;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanExecutionEvaluator;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanOutcomeEvidence;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanReflectionBudget;
import org.wwz.ai.domain.agent.runtime.evaluation.RegisteredArtifactOutcomeEvidenceAdapter;
import org.wwz.ai.domain.agent.service.execute.planexecute.step.Step2PlanExecuteNode;

import java.util.List;

public class PlanSolveOutcomeEvidenceWiringTest {

    @Test
    public void shouldVerifyCurrentRoundRegisteredArtifactThroughPlanSolveEvaluator() {
        AgentContext context = newContext();
        context.registerGeneratedArtifact(source("call-report"), File.builder()
                .fileName("report.md")
                .ossUrl("https://files.example.test/reports/report.md")
                .isInternalFile(false)
                .build());

        PlanEvaluationResult result = evaluatorFromPlanSolve(context).evaluate(
                request("生成并交付报告", "call-report"),
                new PlanReflectionBudget(6000)
        );

        Assert.assertTrue(result.accepted());
        Assert.assertTrue(result.failureReasons().isEmpty());
    }

    @Test
    public void shouldRejectRegisteredArtifactWithoutUsableReferenceThroughPlanSolveEvaluator() {
        AgentContext context = newContext();
        context.registerGeneratedArtifact(source("call-report"), File.builder()
                .fileName("report.md")
                .isInternalFile(false)
                .build());

        PlanEvaluationResult result = evaluatorFromPlanSolve(context).evaluate(
                request("生成并交付报告", "call-report"),
                new PlanReflectionBudget(6000)
        );

        Assert.assertFalse(result.accepted());
        Assert.assertTrue(result.failureReasons().stream()
                .anyMatch(reason -> reason.contains("required artifact outcome is not verified")));
    }

    @Test
    public void shouldIgnoreArtifactsOutsideCurrentExecutorRound() throws Exception {
        AgentContext context = newContext();
        context.registerGeneratedArtifact(source("call-previous"), File.builder()
                .fileName("previous.md")
                .ossUrl("https://files.example.test/reports/previous.md")
                .isInternalFile(false)
                .build());
        context.registerGeneratedArtifact(source("call-current"), File.builder()
                .fileName("scratch.md")
                .isInternalFile(true)
                .build());
        context.registerGeneratedArtifact(ToolArtifactSource.builder()
                .requestId("another-request")
                .sessionId("session-outcome-1")
                .toolCallId("call-current")
                .toolName("report_tool")
                .build(), File.builder()
                .fileName("foreign.md")
                .ossUrl("https://files.example.test/reports/foreign.md")
                .isInternalFile(false)
                .build());

        List<PlanOutcomeEvidence> evidence = new RegisteredArtifactOutcomeEvidenceAdapter(context)
                .collect(request("执行当前检索", "call-current"));

        Assert.assertTrue(evidence.isEmpty());
    }

    @Test
    public void shouldNotInferRequiredArtifactFromNaturalLanguage() {
        AgentContext context = newContext();
        PlanEvaluationRequest request = new PlanEvaluationRequest(
                "请生成一个报告文件",
                "生成报告",
                "本轮只完成了内容分析，结果完整且没有声明任何文件产物。",
                List.of(),
                AgentState.FINISHED,
                1
        );

        PlanEvaluationResult result = evaluatorFromPlanSolve(context).evaluate(
                request,
                new PlanReflectionBudget(6000)
        );

        Assert.assertTrue(result.accepted());
    }

    private PlanExecutionEvaluator evaluatorFromPlanSolve(AgentContext context) {
        return new ExposedPlanExecuteNode().evaluator(context, policy());
    }

    private PlanEvaluationRequest request(String task, String toolCallId) {
        ToolCall toolCall = ToolCall.builder()
                .id(toolCallId)
                .type("function")
                .function(ToolCall.Function.builder()
                        .name("report_tool")
                        .arguments("{}")
                        .build())
                .build();
        return new PlanEvaluationRequest(
                "完成一个可验证的交付任务",
                task,
                "工具执行成功，并返回了足够完整的结构化交付结果。",
                List.of(
                        Message.fromToolCalls("调用报告工具", List.of(toolCall)),
                        Message.toolMessage("report tool completed successfully", toolCallId, null)
                ),
                AgentState.FINISHED,
                1
        );
    }

    private AgentContext newContext() {
        return AgentContext.builder()
                .requestId("request-outcome-1")
                .sessionId("session-outcome-1")
                .build();
    }

    private ToolArtifactSource source(String toolCallId) {
        return ToolArtifactSource.builder()
                .requestId("request-outcome-1")
                .sessionId("session-outcome-1")
                .toolCallId(toolCallId)
                .toolName("report_tool")
                .build();
    }

    private PlanEvaluationPolicy policy() {
        return new PlanEvaluationPolicy(
                true,
                false,
                75,
                2,
                6000,
                10,
                12000,
                600,
                0d,
                "test-model"
        );
    }

    private static final class ExposedPlanExecuteNode extends Step2PlanExecuteNode {

        private PlanExecutionEvaluator evaluator(AgentContext context, PlanEvaluationPolicy policy) {
            return createPlanExecutionEvaluator(context, policy);
        }
    }
}
