package com.linrun.agent.config.reactor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.adapter.port.ModelCatalogPort;
import com.linrun.agent.domain.agent.adapter.port.PlatformContextPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.runtime.llm.DomainMessageConverter;
import com.linrun.agent.domain.agent.runtime.llm.LlmChatModelResolver;
import com.linrun.agent.domain.agent.runtime.llm.LlmChatResponseMapper;
import com.linrun.agent.domain.agent.runtime.llm.OpenAiChatOptionsFactory;
import com.linrun.agent.domain.agent.runtime.llm.StreamResponseHandler;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolExecutor;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.ReactorLlmDependencies;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.reactor.service.imagegeneration.IImageGenerationExecutionKernel;
import com.linrun.agent.types.agent.config.AgentExecutorNames;
import com.linrun.agent.types.agent.config.AgentExecutorProperties;

import java.util.concurrent.Executor;

/**
 * Reactor 运行时依赖装配。
 * app 负责把 Spring Bean 组装成 domain 可消费的 typed runtime bundle，
 * 不在此处承接执行编排或 controller 协议适配。
 */
@Configuration
public class ReactorRuntimeAutoConfiguration {

    @Bean
    public ReactorLlmDependencies reactorLlmDependencies(LlmChatModelResolver chatModelResolver,
                                                         OpenAiChatOptionsFactory chatOptionsFactory,
                                                         DomainMessageConverter messageConverter,
                                                         LlmChatResponseMapper responseMapper,
                                                         StreamResponseHandler streamResponseHandler) {
        return ReactorLlmDependencies.builder()
                .chatModelResolver(chatModelResolver)
                .chatOptionsFactory(chatOptionsFactory)
                .messageConverter(messageConverter)
                .responseMapper(responseMapper)
                .streamResponseHandler(streamResponseHandler)
                .build();
    }

    @Bean
    public ReactorRuntimeDependencies reactorRuntimeDependencies(ReactorConfig reactorConfig,
                                                                 Environment environment,
                                                                 ReactorLlmDependencies reactorLlmDependencies,
                                                                 McpToolExecutor mcpToolExecutor,
                                                                 IImageGenerationExecutionKernel imageGenerationExecutionKernel,
                                                                 RemoteHttpPort remoteHttpPort,
                                                                 RemoteStreamPort remoteStreamPort,
                                                                 FileArtifactPort fileArtifactPort,
                                                                 ModelCatalogPort modelCatalogPort,
                                                                 QuotaBillingPort quotaBillingPort,
                                                                 PlatformContextPort platformContextPort,
                                                                 @Qualifier(AgentExecutorNames.LLM_EXECUTOR) Executor llmExecutor,
                                                                 @Qualifier(AgentExecutorNames.TASK_EXECUTOR) Executor taskExecutor,
                                                                 @Qualifier(AgentExecutorNames.TOOL_EXECUTOR) Executor toolExecutor,
                                                                 @Qualifier(AgentExecutorNames.HEARTBEAT_SCHEDULER) TaskScheduler heartbeatScheduler,
                                                                 AgentExecutorProperties executorProperties) {
        return ReactorRuntimeDependencies.builder()
                .reactorConfig(reactorConfig)
                .environment(environment)
                .llmDependencies(reactorLlmDependencies)
                .mcpToolExecutor(mcpToolExecutor)
                .imageGenerationExecutionKernel(imageGenerationExecutionKernel)
                .remoteHttpPort(remoteHttpPort)
                .remoteStreamPort(remoteStreamPort)
                .fileArtifactPort(fileArtifactPort)
                .modelCatalogPort(modelCatalogPort)
                .quotaBillingPort(quotaBillingPort)
                .platformContextPort(platformContextPort)
                .llmExecutor(llmExecutor)
                .taskExecutor(taskExecutor)
                .toolExecutor(toolExecutor)
                .heartbeatScheduler(heartbeatScheduler)
                .runHeartbeatIntervalMillis(executorProperties.getHeartbeat().getIntervalMillis())
                .build();
    }
}
