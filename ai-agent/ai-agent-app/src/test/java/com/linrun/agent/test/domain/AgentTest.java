package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.model.entity.ArmoryCommandEntity;
import com.linrun.agent.domain.agent.model.valobj.enums.AiAgentEnumVO;
import com.linrun.agent.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import com.linrun.agent.types.design.tree.StrategyHandler;
import com.linrun.agent.types.common.JsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import com.linrun.agent.domain.agent.service.runtime.AiClientRuntimeRegistry;

import java.util.Arrays;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AgentTest {

    @Resource
    private DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;

    @Resource
    private AiClientRuntimeRegistry aiClientRuntimeRegistry;

    @Test
    public void test_aiClientApi() throws Exception {
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> armoryStrategyHandler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();

        String apply = armoryStrategyHandler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT_API.getCode())
                        .commandIdList(Arrays.asList("1001"))
                        .build(),
                new DefaultArmoryStrategyFactory.DynamicContext());

        OpenAiApi openAiApi = aiClientRuntimeRegistry.getRequiredApi("1001");

        log.info("测试结果：{}", openAiApi);
    }

    @Test
    public void test_aiClientApiNode() throws Exception {
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> armoryStrategyHandler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();

        String apply = armoryStrategyHandler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(Arrays.asList("3001"))
                        .build(),
                new DefaultArmoryStrategyFactory.DynamicContext());

        OpenAiApi openAiApi = aiClientRuntimeRegistry.getRequiredApi("1001");

        log.info("测试结果：{}", openAiApi);
    }

    @Test
    public void test_aiClientModelNode() throws Exception {
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> armoryStrategyHandler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();

        String apply = armoryStrategyHandler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(Arrays.asList("5001"))
                        .build(),
                new DefaultArmoryStrategyFactory.DynamicContext());

        ChatModel openAiChatModel = aiClientRuntimeRegistry.getRequiredModel("2004");

        log.info("模型构建:{}", openAiChatModel);

        // 1. 有哪些工具可以使用
        // 2. 在 /Users/fuzhengwei/Desktop 创建 txt.md 文件
        Prompt prompt = Prompt.builder()
                .messages(new UserMessage(
                        """
                                有哪些工具可以使用
                                """))
                .build();

        ChatResponse chatResponse = openAiChatModel.call(prompt);

        log.info("测试结果(call):{}", JsonUtils.toJson(chatResponse));
    }

    @Test
    public void test_aiClient() throws Exception {
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> armoryStrategyHandler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();

        String apply = armoryStrategyHandler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(Arrays.asList("3001"))
                        .build(),
                new DefaultArmoryStrategyFactory.DynamicContext());

        ChatClient chatClient = aiClientRuntimeRegistry.getRequiredChatClient("3001");
        log.info("客户端构建:{}", chatClient);

        String content = chatClient.prompt(Prompt.builder()
                .messages(new UserMessage(
                        """
                                有哪些工具可以使用
                                """))
                .build()).call().content();

        log.info("测试结果(call):{}", content);
    }

}
