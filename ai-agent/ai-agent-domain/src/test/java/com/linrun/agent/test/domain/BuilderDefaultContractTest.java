package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.model.valobj.AiClientAdvisorVO;
import com.linrun.agent.domain.agent.reactor.model.multi.EventResult;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;

/**
 * 锁定 Lombok Builder 与无参构造器一致的默认值语义。
 */
public class BuilderDefaultContractTest {

    @Test
    public void eventResultBuilderShouldCreateUsableMutableState() {
        EventResult result = EventResult.builder().build();

        Assert.assertEquals(Integer.valueOf(1), result.getAndIncrOrder("message"));
        Assert.assertEquals(1, result.getTaskOrder().get());
        Assert.assertTrue(result.getResultMap().isEmpty());
        Assert.assertNotNull(result.getTaskId());
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
    public void processResultBuilderShouldPreserveWireDefaults() {
        GptProcessResult gptProcessResult = GptProcessResult.builder().build();

        Assert.assertEquals("", gptProcessResult.getResponse());
        Assert.assertEquals("", gptProcessResult.getResponseAll());
        Assert.assertEquals("markdown", gptProcessResult.getResponseType());
        Assert.assertEquals("result", gptProcessResult.getPackageType());
    }
}
