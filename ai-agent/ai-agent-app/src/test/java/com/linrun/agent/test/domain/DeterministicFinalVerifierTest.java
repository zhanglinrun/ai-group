package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.completion.CompletionDecision;
import com.linrun.agent.domain.agent.runtime.completion.CompletionRequest;
import com.linrun.agent.domain.agent.runtime.completion.DeterministicFinalVerifier;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;

import java.util.List;

public class DeterministicFinalVerifierTest {

    private final DeterministicFinalVerifier verifier = new DeterministicFinalVerifier();

    @Test
    public void shouldRejectIncompleteMultiProductComparison() {
        CompletionDecision decision = verifier.verify(request(
                "Codex 和 Claude Code 的能力不同，但没有第三款产品的信息。",
                List.of("调研 Codex", "调研 Claude Code", "整理结论")
        ));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream().anyMatch(reason -> reason.contains("Cursor")));
        Assert.assertTrue(decision.getReasons().stream().anyMatch(reason -> reason.contains("price")));
    }

    @Test
    public void shouldAllowComparisonCoveringEverySubjectAndDimension() {
        CompletionDecision decision = verifier.verify(request(
                "产品 | 价格 | 能力\nCodex | 按订阅计费 | 代码任务\n"
                        + "Claude Code | 按订阅计费 | 终端代理\nCursor | 按订阅计费 | IDE 集成",
                List.of(
                        "核对 Codex 的价格和能力",
                        "核对 Claude Code 的价格和能力",
                        "核对 Cursor 的价格和能力"
                )
        ));

        Assert.assertTrue(decision.isCanStop());
    }

    @Test
    public void shouldRejectNamesAndGlobalDimensionLabelsWithoutPerSubjectDetails() {
        CompletionDecision decision = verifier.verify(request(
                "Codex、Claude Code、Cursor；价格和能力。",
                completeComparisonSteps()
        ));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("Codex×price")));
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("Cursor×capability")));
    }

    @Test
    public void shouldRejectWhenDifferentSubjectsMissDifferentDimensions() {
        CompletionDecision decision = verifier.verify(request(
                "Codex：价格按订阅计费；能力支持代码任务。\n"
                        + "Claude Code：能力支持终端代理。\n"
                        + "Cursor：价格按订阅计费。",
                completeComparisonSteps()
        ));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("Claude Code×price")));
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("Cursor×capability")));
    }

    @Test
    public void shouldRejectPlaceholderCellsInComparisonTable() {
        CompletionDecision decision = verifier.verify(request(
                "产品 | 价格 | 能力\n--- | --- | ---\n"
                        + "Codex | 按订阅计费 | 代码任务\n"
                        + "Claude Code | N/A | 终端代理\n"
                        + "Cursor | 按订阅计费 | 未知",
                completeComparisonSteps()
        ));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("Claude Code×price")));
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("Cursor×capability")));
    }

    @Test
    public void shouldNotTreatSubjectNamesInDimensionColumnsAsCoverage() {
        CompletionDecision decision = verifier.verify(request(
                "产品 | 价格 | 能力\n--- | --- | ---\n"
                        + "占位一 | Codex | Claude Code\n"
                        + "占位二 | Cursor | Codex\n"
                        + "占位三 | Claude Code | Cursor",
                completeComparisonSteps()
        ));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("subject-by-dimension")));
    }

    @Test
    public void shouldNotTreatOtherSubjectNamesAsNarrativeDimensionValues() {
        CompletionDecision decision = verifier.verify(request(
                "Codex：价格 Claude Code；能力 Cursor。"
                        + "Claude Code：价格 Codex；能力 Cursor。"
                        + "Cursor：价格 Codex；能力 Claude Code。",
                completeComparisonSteps()
        ));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("subject-by-dimension")));
    }

    private List<String> completeComparisonSteps() {
        return List.of(
                "核对 Codex 的价格和能力",
                "核对 Claude Code 的价格和能力",
                "核对 Cursor 的价格和能力"
        );
    }

    private CompletionRequest request(String answer, List<String> steps) {
        return CompletionRequest.builder()
                .goal("对比 Codex、Claude Code、Cursor 的价格和能力")
                .draftAnswer(answer)
                .executionProfile(AgentExecutionProfile.DEEP)
                .todoList(TodoList.builder()
                        .title("产品对比")
                        .steps(steps)
                        .stepStatus(steps.stream().map(ignored -> "completed").toList())
                        .notes(steps.stream().map(ignored -> "").toList())
                        .evidenceRefs(steps.stream().map(ignored -> List.<String>of("call-evidence")).toList())
                        .build())
                .build();
    }
}
