package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.agent.ExplicitToolChoicePolicy;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolChoice;

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
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve(
                        "必须且只能调用 MCP 工具 utility_estimate_llm_quota 一次", 1));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve(
                        "必须先建立 Todo，并调用 MCP 工具 utility_estimate_llm_quota", 1));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve("只能调用 report_tool", 1));
    }

    @Test
    public void shouldReturnToAutoAfterFirstStep() {
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("必须调用代码执行工具计算平均值", 2));
    }

    @Test
    public void shouldHonorNegativeAndOrdinaryRequests() {
        Assert.assertEquals(ToolChoice.NONE,
                ExplicitToolChoicePolicy.resolve("无需使用任何工具，直接回答", 0));
        Assert.assertEquals(ToolChoice.NONE,
                ExplicitToolChoicePolicy.resolve("无需使用任何工具，直接回答", 3));
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("不调用联网工具，请设计缓存", 0));
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("并非必须调用 report_tool，解释接口即可", 0));
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("不要求必须调用 report_tool，解释接口即可", 0));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve(
                        "必须调用 utility_estimate_llm_quota，禁止使用 code_interpreter 或任何替代工具", 0));
        Assert.assertEquals(ToolChoice.REQUIRED,
                ExplicitToolChoicePolicy.resolve(
                        "必须调用 utility_estimate_llm_quota，禁止使用工具 code_interpreter", 0));
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("请使用 Java 21 实现缓存", 0));
        Assert.assertEquals(ToolChoice.AUTO,
                ExplicitToolChoicePolicy.resolve("设计一个二分搜索算法", 0));
    }

    @Test
    public void shouldClassifyExplicitNetworkRequirementAndNetworkTools() {
        Assert.assertTrue(ExplicitToolChoicePolicy.requiresNetworkLookup("请联网搜索并查证官方价格"));
        Assert.assertTrue(ExplicitToolChoicePolicy.requiresNetworkLookup("查阅 GitHub 最新 release"));
        Assert.assertFalse(ExplicitToolChoicePolicy.requiresNetworkLookup("不要联网，直接解释设计思路"));
        Assert.assertTrue(ExplicitToolChoicePolicy.isNetworkLookupToolName("deep_search"));
        Assert.assertTrue(ExplicitToolChoicePolicy.isNetworkLookupToolName("mcp__github__search_code"));
        Assert.assertTrue(ExplicitToolChoicePolicy.isNetworkLookupToolName("mcp__docs__fetch_page"));
        Assert.assertFalse(ExplicitToolChoicePolicy.isNetworkLookupToolName("tool_search"));
        Assert.assertFalse(ExplicitToolChoicePolicy.isNetworkLookupToolName("report_tool"));
        Assert.assertFalse(ExplicitToolChoicePolicy.isNetworkLookupToolName("mcp__github__create_issue"));
        Assert.assertFalse(ExplicitToolChoicePolicy.isNetworkLookupToolName("mcp__webhook__post_event"));
        Assert.assertFalse(ExplicitToolChoicePolicy.isNetworkLookupToolName("mcp__browser__click"));
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
    public void shouldScopePositiveTargetAwayFromForbiddenAlternatives() {
        List<String> available = List.of(
                "mcp__utility__utility_estimate_llm_quota",
                "code_interpreter",
                "report_tool"
        );

        Assert.assertEquals("mcp__utility__utility_estimate_llm_quota",
                ExplicitToolChoicePolicy.resolveRequiredToolName(
                        "必须调用 MCP 工具 utility_estimate_llm_quota 一次，"
                                + "禁止使用 code_interpreter、report_tool 或任何替代工具。",
                        1,
                        available));
    }

    @Test
    public void shouldRetainUnavailableAndAmbiguousNamedRequirements() {
        ExplicitToolChoicePolicy.ExplicitToolRequirement unavailable =
                ExplicitToolChoicePolicy.inspectRequiredTool(
                        "必须且只能调用 MCP 工具 utility_estimate_llm_quota",
                        1,
                        List.of("code_interpreter"));
        Assert.assertEquals(ExplicitToolChoicePolicy.RequirementResolution.UNAVAILABLE,
                unavailable.resolution());
        Assert.assertEquals("utility_estimate_llm_quota", unavailable.requestedToolName());
        Assert.assertTrue(unavailable.shouldFailFast());

        ExplicitToolChoicePolicy.ExplicitToolRequirement ambiguous =
                ExplicitToolChoicePolicy.inspectRequiredTool(
                        "必须调用 MCP 工具 utility_estimate_llm_quota",
                        1,
                        List.of(
                                "mcp__utility-a__utility_estimate_llm_quota",
                                "mcp__utility-b__utility_estimate_llm_quota"));
        Assert.assertEquals(ExplicitToolChoicePolicy.RequirementResolution.AMBIGUOUS,
                ambiguous.resolution());
        Assert.assertTrue(ambiguous.shouldFailFast());
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
        Assert.assertEquals(ToolChoice.NONE, ExplicitToolChoicePolicy.resolveForCurrentTask(
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

    @Test
    public void shouldResolveRawMcpAliasToCanonicalExposedName() {
        List<String> available = List.of(
                "mcp__agent-utility__utility_estimate_llm_quota",
                "report_tool"
        );

        Assert.assertEquals("mcp__agent-utility__utility_estimate_llm_quota",
                ExplicitToolChoicePolicy.resolveSingleUseRequiredToolName(
                        "必须调用 MCP 工具 utility_estimate_llm_quota，整个运行中只调用这一个工具一次。",
                        available));
    }
}
