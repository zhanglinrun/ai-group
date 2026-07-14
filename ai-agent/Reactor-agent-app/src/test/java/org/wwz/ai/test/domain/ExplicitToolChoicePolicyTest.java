package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.ExplicitToolChoicePolicy;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolChoice;

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
}
