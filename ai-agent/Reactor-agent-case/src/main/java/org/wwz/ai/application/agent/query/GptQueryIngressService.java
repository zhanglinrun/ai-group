package org.wwz.ai.application.agent.query;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.model.IModelCatalogQueryService;
import org.wwz.ai.application.agent.quota.AgentRunSettlementService;
import org.wwz.ai.application.agent.quota.MemberQuotaBillingService;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.application.agent.stream.QuotaBillingAgentSessionStream;
import org.wwz.ai.application.agent.visitor.ConversationSessionOwnershipApplicationService;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.reactor.model.req.GptQueryReq;
import org.wwz.ai.domain.agent.reactor.util.ChateiUtils;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.types.agent.owner.OwnerRequestContext;

@Service
@RequiredArgsConstructor
public class GptQueryIngressService {

    private final IAgentDispatchService agentDispatchService;
    private final MemberQuotaBillingService memberQuotaBillingService;
    private final ConversationSessionOwnershipApplicationService conversationSessionOwnershipApplicationService;
    private final ReactorConfig reactorConfig;
    private final AgentRunSettlementService agentRunSettlementService;
    private final IModelCatalogQueryService modelCatalogQueryService;

    /**
     * 同步执行入口：冻结配额 → dispatch → 按账本终态结算。
     * 保留给 legacy ReactorController 与单测使用；主链路（AiAgentController）走 prepare + 异步 dispatchAndSettle。
     */
    public void queryAgentStreamIncr(GptQueryReq params, AgentSessionStream stream) {
        PreparedGptQuery prepared = prepare(params, stream);
        dispatchAndSettle(prepared, true);
    }

    /**
     * 同步准备阶段：解析身份、构建请求、校验会话归属、冻结配额。
     * 放在 Servlet 线程执行，使配额不足/归属校验失败能立刻返回，而不占用 dispatch 线程池。
     */
    public PreparedGptQuery prepare(GptQueryReq params, AgentSessionStream stream) {
        Long ownerId = OwnerRequestContext.requireOwnerId();
        params.setTraceId(ChateiUtils.getRequestId(params));
        validateModelSelection(params.getModelId());
        AgentRequest agentRequest = buildAgentRequest(params, ownerId);
        conversationSessionOwnershipApplicationService.ensureSessionAccessible(
                String.valueOf(ownerId),
                params.getSessionId(),
                params.getQuery()
        );
        String freezeId = memberQuotaBillingService.freezeForAgentRun(ownerId, agentRequest);
        QuotaBillingAgentSessionStream billingStream = new QuotaBillingAgentSessionStream(stream, memberQuotaBillingService, freezeId);
        billingStream.onAbort(() -> {
            // Register quota release on downstream SSE abort even when the execute strategy has no upstream stream to cancel.
        });
        return new PreparedGptQuery(agentRequest, billingStream, freezeId);
    }

    /**
     * 执行阶段：dispatch + 结算。可在 dispatch 线程池异步执行。
     *
     * @param rethrow true 时（同步路径）dispatch 异常向上抛出；false 时（异步路径）仅落到流的 completeWithError。
     */
    public void dispatchAndSettle(PreparedGptQuery prepared, boolean rethrow) {
        AgentRequest agentRequest = prepared.agentRequest();
        QuotaBillingAgentSessionStream billingStream = prepared.billingStream();
        try {
            agentDispatchService.dispatch(agentRequest, billingStream);
            // dispatch 正常返回不代表执行成功（agent 失败分支会吞异常），以账本 run 终态决定 confirm/release。
            if (agentRunSettlementService.shouldReleaseAfterDispatch(agentRequest.getRequestId())) {
                billingStream.completeWithFailureSettlement();
            } else {
                billingStream.complete();
            }
        } catch (Exception ex) {
            billingStream.completeWithError(ex);
            if (rethrow) {
                if (ex instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * 准备阶段产物：携带已冻结配额的请求、结算流与冻结ID，供异步执行阶段消费。
     */
    public record PreparedGptQuery(AgentRequest agentRequest,
                                   QuotaBillingAgentSessionStream billingStream,
                                   String freezeId) {
    }

    /**
     * 用户显式选择模型时做白名单校验：非法/不可用直接拒绝（与配额拒绝同路径，Servlet 线程内快速失败）。
     * 未选择模型（空）时放行，走默认模型逻辑。
     */
    private void validateModelSelection(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return;
        }
        if (!modelCatalogQueryService.isModelAvailable(modelId)) {
            throw new IllegalArgumentException("所选模型不可用或不存在: " + modelId);
        }
    }

    private AgentRequest buildAgentRequest(GptQueryReq req, Long ownerId) {
        AgentRequest request = new AgentRequest();
        request.setRequestId(req.getTraceId());
        request.setSessionId(req.getSessionId());
        request.setOwnerId(String.valueOf(ownerId));
        request.setQuery(req.getQuery());
        request.setSessionFiles(req.getSessionFiles());
        request.setModelId(req.getModelId());
        request.setIsStream(true);
        request.setOutputStyle(req.getOutputStyle());
        if ("chat".equalsIgnoreCase(req.getOutputStyle())) {
            request.setAgentType(AgentType.WORKFLOW.getValue());
            request.setSopPrompt("");
        } else {
            Integer agentType = (req.getDeepThink() == null || req.getDeepThink() == 0)
                    ? AgentType.REACT.getValue()
                    : AgentType.PLAN_SOLVE.getValue();
            request.setAgentType(agentType);
            request.setSopPrompt(agentType.equals(AgentType.PLAN_SOLVE.getValue())
                    ? reactorConfig.getReactorSopPrompt()
                    : "");
            request.setBasePrompt(agentType.equals(AgentType.REACT.getValue())
                    ? reactorConfig.getReactorBasePrompt()
                    : "");
        }
        if (StringUtils.isNotBlank(req.getAiAgentId())) {
            request.setAiAgentId(req.getAiAgentId());
        } else if ("chat".equalsIgnoreCase(req.getOutputStyle())
                && StringUtils.isNotBlank(reactorConfig.getChatDefaultRoleId())) {
            request.setAiAgentId(reactorConfig.getChatDefaultRoleId());
        }
        return request;
    }
}
