package org.wwz.ai.application.agent.quota;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.adapter.port.QuotaBillingPort;

@Service
@RequiredArgsConstructor
public class MemberQuotaBillingService implements QuotaBillingPort {

    private static final int SUCCESS_CODE = 200;

    private final MemberQuotaFeignClient memberQuotaFeignClient;

    @Override
    public Reservation reserve(Long userId, long requestedMicrocredits, long minimumMicrocredits, String requestId) {
        QuotaFreezeRequest freezeRequest = QuotaFreezeRequest.builder()
                .userId(userId)
                .amount(requestedMicrocredits)
                .minAmount(minimumMicrocredits)
                .abilityCode("llm_call")
                .requestId(requestId)
                .build();
        MemberQuotaResult<QuotaFreezeVO> result = memberQuotaFeignClient.freeze(freezeRequest);
        QuotaFreezeVO data = requireFreeze(result);
        return new Reservation(data.getFreezeId(), data.getAmount());
    }

    @Override
    public void settle(String freezeId, long actualMicrocredits) {
        if (freezeId == null) {
            return;
        }
        MemberQuotaResult<Void> result = memberQuotaFeignClient.confirm(QuotaFreezeActionRequest.builder()
                .freezeId(freezeId)
                .actualAmount(actualMicrocredits)
                .build());
        requireSuccess(result, "配额结算失败");
    }

    private QuotaFreezeVO requireFreeze(MemberQuotaResult<QuotaFreezeVO> result) {
        requireSuccess(result, "配额预扣失败");
        if (result.getData() == null || result.getData().getFreezeId() == null || result.getData().getAmount() == null) {
            throw new QuotaInsufficientException("配额预扣失败");
        }
        return result.getData();
    }

    private void requireSuccess(MemberQuotaResult<?> result, String fallback) {
        if (result == null) {
            throw new QuotaInsufficientException("配额服务无响应");
        }
        if (!Integer.valueOf(SUCCESS_CODE).equals(result.getCode())) {
            throw new QuotaInsufficientException(result.getMessage() == null ? fallback : result.getMessage());
        }
    }

    public void release(String freezeId) {
        if (freezeId == null) {
            return;
        }
        memberQuotaFeignClient.release(QuotaFreezeActionRequest.builder().freezeId(freezeId).build());
    }

}

