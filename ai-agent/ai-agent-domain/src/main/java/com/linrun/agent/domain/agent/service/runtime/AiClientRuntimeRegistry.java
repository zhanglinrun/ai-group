package com.linrun.agent.domain.agent.service.runtime;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * AI Client 运行时注册表。
 * 用显式业务 ID 读写运行时对象，避免 domain 通过 Spring Bean 名称做动态查找。
 */
public interface AiClientRuntimeRegistry {

    /**
     * 组合对话客户端的注册键：{@code clientId::modelId}。
     * 用于 Agent 角色按"用户所选模型"取用同一 client 的不同模型客户端。
     */
    static String comboClientKey(String clientId, String modelId) {
        return clientId + "::" + modelId;
    }

    void registerApi(String apiId, OpenAiApi openAiApi);

    void registerModel(String modelId, ChatModel chatModel);

    void registerAdvisor(String advisorId, Advisor advisor);

    void registerChatClient(String clientId, ChatClient chatClient);

    OpenAiApi getRequiredApi(String apiId);

    ChatModel getRequiredModel(String modelId);

    Advisor getRequiredAdvisor(String advisorId);

    ChatClient getRequiredChatClient(String clientId);

    /**
     * 查找已注册模型，未命中返回 {@code null}（用于装配期判断某模型是否已就绪）。
     */
    ChatModel findModel(String modelId);

    /**
     * 查找已注册对话客户端，未命中返回 {@code null}（用于 chat 模式按 clientId::modelId 取组合客户端并安全回退）。
     */
    ChatClient findChatClient(String clientId);
}
