package com.linrun.agent.trigger.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.adapter.port.ModelCatalogPort;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.reactor.model.req.GptQueryReq;
import com.linrun.agent.domain.agent.reactor.util.ChateiUtils;
import com.linrun.agent.domain.agent.runtime.enums.AgentType;
import com.linrun.agent.domain.agent.service.dispatch.IAgentDispatchService;
import com.linrun.agent.domain.agent.service.session.ConversationSessionOwnershipService;
import com.linrun.agent.trigger.stream.AgentSessionPrinter;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class GptQueryIngressService {

    private final IAgentDispatchService agentDispatchService;
    private final ConversationSessionOwnershipService conversationSessionOwnershipService;
    private final ReactorConfig reactorConfig;
    private final ModelCatalogPort modelCatalogPort;
    private final AgentStreamEventStore streamEventStore;

    public GptQueryIngressService(IAgentDispatchService agentDispatchService,
                                  ConversationSessionOwnershipService conversationSessionOwnershipService,
                                  ReactorConfig reactorConfig,
                                  ModelCatalogPort modelCatalogPort) {
        this(agentDispatchService, conversationSessionOwnershipService, reactorConfig, modelCatalogPort,
                (AgentStreamEventStore) null);
    }

    @Autowired
    public GptQueryIngressService(IAgentDispatchService agentDispatchService,
                                  ConversationSessionOwnershipService conversationSessionOwnershipService,
                                  ReactorConfig reactorConfig,
                                  ModelCatalogPort modelCatalogPort,
                                  ObjectProvider<AgentStreamEventStore> streamEventStore) {
        this(agentDispatchService, conversationSessionOwnershipService, reactorConfig, modelCatalogPort,
                streamEventStore.getIfAvailable());
    }

    private GptQueryIngressService(IAgentDispatchService agentDispatchService,
                                   ConversationSessionOwnershipService conversationSessionOwnershipService,
                                   ReactorConfig reactorConfig,
                                   ModelCatalogPort modelCatalogPort,
                                   AgentStreamEventStore streamEventStore) {
        this.agentDispatchService = agentDispatchService;
        this.conversationSessionOwnershipService = conversationSessionOwnershipService;
        this.reactorConfig = reactorConfig;
        this.modelCatalogPort = modelCatalogPort;
        this.streamEventStore = streamEventStore;
    }

    /**
     * 同步执行入口：校验请求 → dispatch；具体模型调用在运行时按调用预留并结算额度。
     * 保留给同步嵌入式调用与单测使用；主链路（AiAgentController）走 prepare + 异步 dispatchAndSettle。
     */
    public void queryAgentStreamIncr(GptQueryReq params, AgentMessageStream stream) {
        PreparedGptQuery prepared = prepare(params, stream);
        dispatchAndSettle(prepared, true);
    }

    /**
     * 同步准备阶段：解析身份、构建请求、校验模型与会话归属。
     * 放在 Servlet 线程执行，使无效请求能立刻返回，而不占用 dispatch 线程池。
     */
    public PreparedGptQuery prepare(GptQueryReq params, AgentMessageStream stream) {
        Long ownerId = OwnerRequestContext.requireOwnerId();
        params.setTraceId(ChateiUtils.getRequestId(params));
        validateModelSelection(params.getModelId());
        AgentRequest agentRequest = buildAgentRequest(params, ownerId);
        conversationSessionOwnershipService.ensureSessionAccessible(
                String.valueOf(ownerId),
                params.getSessionId(),
                params.getQuery()
        );
        return new PreparedGptQuery(agentRequest, stream);
    }

    /**
     * 执行阶段：dispatch + 结算。可在 dispatch 线程池异步执行。
     *
     * @param rethrow true 时（同步路径）dispatch 异常向上抛出；false 时（异步路径）仅落到流的 completeWithError。
     */
    public void dispatchAndSettle(PreparedGptQuery prepared, boolean rethrow) {
        AgentRequest agentRequest = prepared.agentRequest();
        AgentMessageStream stream = prepared.stream();
        try {
            agentDispatchService.dispatch(agentRequest,
                    new AgentSessionPrinter(stream, agentRequest, streamEventStore));
            stream.complete();
        } catch (Exception ex) {
            stream.completeWithError(ex);
            if (rethrow) {
                if (ex instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * 准备阶段产物：携带请求与响应流，供异步执行阶段消费。
     */
    public record PreparedGptQuery(AgentRequest agentRequest,
                                   AgentMessageStream stream) {
    }

    /**
     * 用户显式选择模型时做白名单校验：非法/不可用直接拒绝（与配额拒绝同路径，Servlet 线程内快速失败）。
     * 未选择模型（空）时放行，走默认模型逻辑。
     */
    private void validateModelSelection(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return;
        }
        if (!modelCatalogPort.isModelAvailable(modelId)) {
            throw new IllegalArgumentException("所选模型不可用或不存在: " + modelId);
        }
    }

    private AgentRequest buildAgentRequest(GptQueryReq req, Long ownerId) {
        AgentRequest request = new AgentRequest();
        request.setRequestId(req.getTraceId());
        request.setSessionId(req.getSessionId());
        request.setOwnerId(String.valueOf(ownerId));
        request.setQuery(req.getQuery());
        request.setOriginalQuery(req.getQuery());
        request.setSessionFiles(req.getSessionFiles());
        request.setModelId(req.getModelId());
        request.setOnline(req.getOnline());
        request.setIsStream(true);
        String executionMode = resolveExecutionMode(req);
        request.setExecutionMode(executionMode);
        request.setOutputStyle(resolveOutputStyle(req.getOutputStyle(), executionMode));
        request.setAgentType(AgentType.AGENT_LOOP.getValue());
        request.setBasePrompt(reactorConfig.getReactorBasePrompt());
        if (StringUtils.isNotBlank(req.getAiAgentId())) {
            request.setAiAgentId(req.getAiAgentId());
        }
        return request;
    }

    private String resolveExecutionMode(GptQueryReq req) {
        String explicit = StringUtils.upperCase(StringUtils.trimToEmpty(req.getExecutionMode()));
        if ("AUTO".equals(explicit) || "STANDARD".equals(explicit) || "DEEP".equals(explicit)) {
            return explicit;
        }
        return "STANDARD";
    }

    private String resolveOutputStyle(String outputStyle, String executionMode) {
        if ("DEEP".equalsIgnoreCase(executionMode) && StringUtils.isBlank(outputStyle)) {
            return "markdown";
        }
        return outputStyle;
    }

}
