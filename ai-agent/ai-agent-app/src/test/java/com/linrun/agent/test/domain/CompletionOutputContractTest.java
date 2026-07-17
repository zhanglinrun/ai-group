package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.completion.CompletionDecision;
import com.linrun.agent.domain.agent.runtime.completion.CompletionOutputContract;
import com.linrun.agent.domain.agent.runtime.completion.CompletionOutputContractParser;
import com.linrun.agent.domain.agent.runtime.completion.CompletionRequest;
import com.linrun.agent.domain.agent.runtime.completion.DefaultCompletionGate;
import com.linrun.agent.domain.agent.runtime.completion.DeterministicFinalVerifier;
import com.linrun.agent.domain.agent.runtime.completion.ToolExecutionEvidence;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;

import java.util.List;

public class CompletionOutputContractTest {

    private static final String REQUIRED_TOOL =
            "mcp__dev_mcp_agent_utility_001__utility_estimate_llm_quota";
    private static final String MCP_GOAL = "必须调用 MCP 工具 utility_estimate_llm_quota 一次，"
            + "禁止使用 code_interpreter、report_tool、read_tool 或任何替代工具。"
            + "参数：input_tokens=1200、requested_output_tokens=800、actual_output_tokens=600、"
            + "input_microcredits_per_token=2、output_microcredits_per_token=3。"
            + "直接用纯文本列出 requested_microcredits、minimum_microcredits、actual_microcredits、"
            + "within_requested_reservation，不生成文件。只有该 MCP 工具成功才可完成。";

    private final CompletionOutputContractParser parser = new CompletionOutputContractParser();

    @Test
    public void shouldExtractOnlyExplicitOutputFieldsFromCurrentMcpGoal() {
        CompletionOutputContract contract = parser.parse(MCP_GOAL);

        Assert.assertEquals(List.of(
                "requested_microcredits",
                "minimum_microcredits",
                "actual_microcredits",
                "within_requested_reservation"
        ), contract.requiredFields());
        Assert.assertFalse(contract.requiredFields().contains("utility_estimate_llm_quota"));
        Assert.assertFalse(contract.requiredFields().contains("requested_output_tokens"));
    }

    @Test
    public void shouldNotInferContractForOrdinaryOrSingleFieldQuestions() {
        Assert.assertTrue(parser.parse(
                "解释 Java compare_and_set 与 happens_before 的关系").isEmpty());
        Assert.assertTrue(parser.parse(
                "列出 requested_microcredits 的含义").isEmpty());
        Assert.assertTrue(parser.parse(
                "不要在最终答案中输出 debug_trace、raw_payload").isEmpty());
        Assert.assertTrue(parser.parse(
                "调用工具时参数包含 input_tokens、requested_output_tokens").isEmpty());
    }

    @Test
    public void shouldMergeMultipleExplicitOutputClauses() {
        CompletionOutputContract contract = parser.parse(
                "列出 requested_microcredits、minimum_microcredits；"
                        + "返回 actual_microcredits、within_requested_reservation。");

        Assert.assertEquals(List.of(
                "requested_microcredits",
                "minimum_microcredits",
                "actual_microcredits",
                "within_requested_reservation"
        ), contract.requiredFields());
    }

    @Test
    public void outputContractShouldListMissingOutputFields() {
        List<String> missing = parser.parse(MCP_GOAL).missingFrom(
                "requested_microcredits = 4800\nactual_microcredits = 4200");

        Assert.assertEquals(List.of(
                "minimum_microcredits",
                "within_requested_reservation"
        ), missing);
    }

    @Test
    public void completionGateShouldRejectSuccessfulToolCallWhenFinalTextMissesAField() {
        CompletionDecision decision = new DefaultCompletionGate(new DeterministicFinalVerifier())
                .evaluate(request("requested_microcredits = 4800\n"
                        + "minimum_microcredits = 3168\n"
                        + "actual_microcredits = 4200"));

        Assert.assertFalse(decision.isCanStop());
        Assert.assertTrue(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("within_requested_reservation")));
        Assert.assertFalse(decision.getReasons().stream()
                .anyMatch(reason -> reason.contains("was not executed successfully")));
    }

    @Test
    public void completionGateShouldAllowSuccessfulToolCallWhenAllFieldsAppear() {
        CompletionDecision decision = new DefaultCompletionGate(new DeterministicFinalVerifier())
                .evaluate(request("requested_microcredits = 4800\n"
                        + "minimum_microcredits = 3168\n"
                        + "actual_microcredits = 4200\n"
                        + "within_requested_reservation = true"));

        Assert.assertTrue(decision.isCanStop());
        Assert.assertTrue(decision.isVerifierExecuted());
    }

    private CompletionRequest request(String draftAnswer) {
        CompletionOutputContract contract = parser.parse(MCP_GOAL);
        return CompletionRequest.builder()
                .goal(MCP_GOAL)
                .draftAnswer(draftAnswer)
                .executionProfile(AgentExecutionProfile.STANDARD)
                .toolEvidence(List.of(ToolExecutionEvidence.builder()
                        .toolCallId("call-required")
                        .toolName(REQUIRED_TOOL)
                        .success(true)
                        .build()))
                .requiredToolName(REQUIRED_TOOL)
                .requiredOutputFields(contract.requiredFields())
                .build();
    }
}
