package com.linrun.agent.domain.agent.runtime;

import lombok.Builder;
import lombok.Value;
import com.linrun.agent.domain.agent.runtime.llm.DomainMessageConverter;
import com.linrun.agent.domain.agent.runtime.llm.LlmChatModelResolver;
import com.linrun.agent.domain.agent.runtime.llm.LlmChatResponseMapper;
import com.linrun.agent.domain.agent.runtime.llm.OpenAiChatOptionsFactory;
import com.linrun.agent.domain.agent.runtime.llm.StreamResponseHandler;

/**
 * LLM 运行时依赖集合。
 * 统一承接 Spring AI 适配器，避免 LLM 在运行期自行回 Spring 容器拉取 Bean。
 */
@Value
@Builder
public class ReactorLlmDependencies {

    LlmChatModelResolver chatModelResolver;

    OpenAiChatOptionsFactory chatOptionsFactory;

    DomainMessageConverter messageConverter;

    LlmChatResponseMapper responseMapper;

    StreamResponseHandler streamResponseHandler;
}
