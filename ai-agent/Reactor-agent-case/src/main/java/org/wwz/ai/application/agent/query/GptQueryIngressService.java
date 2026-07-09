package org.wwz.ai.application.agent.query;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
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

    public void queryAgentStreamIncr(GptQueryReq params, AgentSessionStream stream) {
        Long ownerId = OwnerRequestContext.requireOwnerId();
        params.setTraceId(ChateiUtils.getRequestId(params));
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
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(ex);
        }
    }

    private AgentRequest buildAgentRequest(GptQueryReq req, Long ownerId) {
        AgentRequest request = new AgentRequest();
        request.setRequestId(req.getTraceId());
        request.setSessionId(req.getSessionId());
        request.setOwnerId(String.valueOf(ownerId));
        request.setQuery(req.getQuery());
        request.setSessionFiles(req.getSessionFiles());
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
