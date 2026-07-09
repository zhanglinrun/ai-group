package org.wwz.ai.application.agent.quota;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;

@Service
@RequiredArgsConstructor
public class MemberQuotaBillingService {

    private static final int SUCCESS_CODE = 200;

    private final MemberQuotaFeignClient memberQuotaFeignClient;

    public String freezeForAgentRun(Long userId, AgentRequest request) {
        return freezeForAbility(userId, resolveAbilityCode(request));
    }

    public String freezeForAbility(Long userId, String abilityCode) {
        QuotaFreezeRequest freezeRequest = QuotaFreezeRequest.builder()
                .userId(userId)
                .abilityCode(abilityCode)
                .multiplier(1)
                .build();
        MemberQuotaResult<QuotaFreezeVO> result = memberQuotaFeignClient.freeze(freezeRequest);
        if (result == null) {
            throw new QuotaInsufficientException("配额服务无响应");
        }
        if (!Integer.valueOf(SUCCESS_CODE).equals(result.getCode())) {
            throw new QuotaInsufficientException(result.getMessage() == null ? "配额不足" : result.getMessage());
        }
        if (result.getData() == null || result.getData().getFreezeId() == null) {
            throw new QuotaInsufficientException("配额预扣失败");
        }
        return result.getData().getFreezeId();
    }

    public void confirm(String freezeId) {
        if (freezeId == null) {
            return;
        }
        memberQuotaFeignClient.confirm(QuotaFreezeActionRequest.builder().freezeId(freezeId).build());
    }

    public void release(String freezeId) {
        if (freezeId == null) {
            return;
        }
        memberQuotaFeignClient.release(QuotaFreezeActionRequest.builder().freezeId(freezeId).build());
    }

    private String resolveAbilityCode(AgentRequest request) {
        if (request == null || request.getAgentType() == null) {
            return "react";
        }
        Integer agentType = request.getAgentType();
        if (AgentType.PLAN_SOLVE.getValue().equals(agentType)) {
            return "plan_solve";
        }
        if (AgentType.WORKFLOW.getValue().equals(agentType)) {
            return "workflow";
        }
        return "react";
    }
}
