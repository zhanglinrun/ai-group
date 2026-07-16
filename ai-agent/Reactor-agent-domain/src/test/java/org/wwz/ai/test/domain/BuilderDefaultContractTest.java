package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import org.wwz.ai.domain.agent.reactor.model.dto.AutoBotsResult;
import org.wwz.ai.domain.agent.reactor.model.multi.EventResult;
import org.wwz.ai.domain.agent.reactor.model.response.GptProcessResult;
import org.wwz.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;

import java.util.List;

/**
 * 锁定 Lombok Builder 与无参构造器一致的默认值语义。
 */
public class BuilderDefaultContractTest {

    @Test
    public void eventResultBuilderShouldCreateUsableMutableState() {
        EventResult result = EventResult.builder().build();

        Assert.assertEquals(0, result.getMessageCount().get());
        Assert.assertEquals(Integer.valueOf(1), result.getAndIncrOrder("message"));
        Assert.assertEquals(1, result.getTaskOrder().get());
        Assert.assertEquals(List.of(
                "html",
                "markdown",
                "knowledge",
                "deep_search",
                "tool_thought",
                "tool_call",
                "data_analysis"
        ), result.getStreamTaskMessageType());
        Assert.assertTrue(result.getResultMap().isEmpty());
        Assert.assertTrue(result.getResultList().isEmpty());

        result.setPlannerRoundId("planner-1");
        Assert.assertEquals("planner-1", result.getPlannerRoundId());
    }

    @Test
    public void armoryDynamicContextBuilderShouldCreateWritableStorage() {
        DefaultArmoryStrategyFactory.DynamicContext context =
                DefaultArmoryStrategyFactory.DynamicContext.builder().build();

        context.setValue("key", "value");

        Assert.assertEquals("value", context.<String>getValue("key"));
    }

    @Test
    public void advisorBuilderShouldPreserveRagRecallDefault() {
        AiClientAdvisorVO.RagAnswer ragAnswer = AiClientAdvisorVO.RagAnswer.builder().build();

        Assert.assertEquals(4, ragAnswer.getTopK());
    }

    @Test
    public void responseBuildersShouldPreserveWireDefaults() {
        AutoBotsResult autoBotsResult = AutoBotsResult.builder().build();
        GptProcessResult gptProcessResult = GptProcessResult.builder().build();

        Assert.assertEquals("", autoBotsResult.getResponse());
        Assert.assertEquals("", autoBotsResult.getResponseAll());
        Assert.assertEquals("markdown", autoBotsResult.getResponseType());
        Assert.assertEquals("", gptProcessResult.getResponse());
        Assert.assertEquals("", gptProcessResult.getResponseAll());
        Assert.assertEquals("markdown", gptProcessResult.getResponseType());
        Assert.assertEquals("result", gptProcessResult.getPackageType());
    }
}
