package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.ExplicitToolChoicePolicy;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolChoice;

import java.util.List;

public class ExplicitToolChoicePolicyTest {

    @Test
    public void shouldRequireExplicitToolOnFirstStep() {
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve("请使用联网搜索工具查证版本", 1));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve("必须调用代码执行工具计算平均值", 1));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve("请加载 chart-visualization Skill", 1));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve("请联网搜索并简要说明 MCP 的用途", 1));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve("查阅 Qdrant 官方文档最新版", 1));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve("最终交付物请调用 report_tool 生成 HTML 网页报告", 1));
    }

    @Test
    public void shouldReturnToAutoAfterFirstStep() {
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("必须调用代码执行工具计算平均值", 2));
    }

    @Test
    public void shouldHonorNegativeAndOrdinaryRequests() {
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("无需使用任何工具，直接回答", 0));
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("不调用联网工具，请设计缓存", 0));
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("请使用 Java 21 实现缓存", 0));
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("设计一个二分搜索算法", 0));
    }

    @Test
    public void shouldResolveOnlyOneExactAvailableToolName() {
        List<String> available = List.of("report_tool", "project_search_knowledge");

        Assert.assertEquals("report_tool", ExplicitToolChoicePolicy.resolveRequiredToolName(
                "最终交付物请调用 report_tool 生成 HTML 网页报告", 1, available));
        Assert.assertNull(ExplicitToolChoicePolicy.resolveRequiredToolName(
                "不要调用 report_tool，直接回答", 1, available));
        Assert.assertNull(ExplicitToolChoicePolicy.resolveRequiredToolName(
                "请调用 report_tool_preview 生成预览", 1, available));
        Assert.assertNull(ExplicitToolChoicePolicy.resolveRequiredToolName(
                "请调用 report_tool 和 project_search_knowledge 工具", 1, available));
    }

    @Test
    public void shouldScopeNamedToolRequirementToCurrentPlanTask() {
        List<String> available = List.of("report_tool", "project_search_knowledge");
        String original = "必须使用 report_tool 生成 HTML 报告";

        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolveForCurrentTask(original, "先梳理报告结构", 1));
        Assert.assertNull(ExplicitToolChoicePolicy.resolveRequiredToolNameForCurrentTask(
                original, "查阅官方文档", 1, available));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolveForCurrentTask(original, "调用 report_tool 生成文件", 1));
        Assert.assertEquals("report_tool", ExplicitToolChoicePolicy.resolveRequiredToolNameForCurrentTask(
                original, "调用 report_tool 生成文件", 1, available));
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolveForCurrentTask(original, "解释 report_tool 调用流程", 1));
        Assert.assertEquals("report_tool", ExplicitToolChoicePolicy.resolveRequiredToolNameForCurrentTask(
                original, "", 1, available));
        Assert.assertEquals(ToolChoice.AUTO, ExplicitToolChoicePolicy.resolveForCurrentTask(
                "不要调用 report_tool", "调用 report_tool 生成文件", 1));
    }

    @Test
    public void shouldResolveExplicitSingleUseToolBudget() {
        List<String> available = List.of("utility_estimate_llm_quota", "report_tool");

        Assert.assertEquals("utility_estimate_llm_quota",
                ExplicitToolChoicePolicy.resolveSingleUseRequiredToolName(
                        "必须调用 MCP 工具 utility_estimate_llm_quota，整个运行中只调用这一个工具一次。",
                        available));
        Assert.assertEquals("utility_estimate_llm_quota",
                ExplicitToolChoicePolicy.resolveSingleUseRequiredToolName(
                        "深度模式验收：必须调用 MCP 工具 utility_estimate_llm_quota：输入 token 1000、"
                                + "请求输出 token 512、实际输出 token 100、输入单价 5、输出单价 30（单位均为 microcredits），"
                                + "并明确列出工具名、预留额度、最低预留额度和实际结算额度。整个运行中只调用这一个工具一次。"
                                + "\n\n[输出格式要求] 最终交付物请调用 report_tool 生成 Markdown 文档报告（fileType=markdown）。",
                        available));
        Assert.assertNull(ExplicitToolChoicePolicy.resolveSingleUseRequiredToolName(
                "必须调用 MCP 工具 utility_estimate_llm_quota。", available));
    }
}
