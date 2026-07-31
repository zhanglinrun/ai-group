package com.linrun.agent.infrastructure.gateway.quota;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.quota.QuotaBillingRequestId;
import com.linrun.agent.domain.agent.quota.QuotaRemoteCallException;
import com.linrun.agent.domain.agent.quota.QuotaSettlementRemotePort;
import com.linrun.agent.infrastructure.gateway.quota.dto.MemberQuotaResult;
import com.linrun.agent.infrastructure.gateway.quota.dto.QuotaFreezeActionRequest;
import com.linrun.agent.infrastructure.gateway.quota.dto.QuotaFreezeRequest;
import com.linrun.agent.infrastructure.gateway.quota.dto.QuotaFreezeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Raw member-service gateway. Durable sequencing belongs to DurableQuotaBillingCoordinator. */
@Component
@RequiredArgsConstructor
public class MemberQuotaBillingAdapter implements QuotaSettlementRemotePort {

    private static final int SUCCESS_CODE = 200;
    private static final int QUOTA_INSUFFICIENT_CODE = 621;

    private final MemberQuotaFeignClient memberQuotaFeignClient;

    @Override
    public RemoteReservationStatus reserveRemote(Long userId,
                                                 long requestedMicrocredits,
                                                 long minimumMicrocredits,
                                                 String abilityCode,
                                                 String billingRequestId) {
        return reserveRemote(userId, requestedMicrocredits, minimumMicrocredits, abilityCode,
                billingRequestId, null);
    }

    @Override
    public RemoteReservationStatus reserveRemote(Long userId,
                                                 long requestedMicrocredits,
                                                 long minimumMicrocredits,
                                                 String abilityCode,
                                                 String billingRequestId,
                                                 String traceId) {
        String normalizedRequestId = QuotaBillingRequestId.normalize(billingRequestId);
        QuotaFreezeRequest freezeRequest = QuotaFreezeRequest.builder()
                .userId(userId)
                .amount(requestedMicrocredits)
                .minAmount(minimumMicrocredits)
                .abilityCode(abilityCode)
                .requestId(normalizedRequestId)
                .traceId(traceId)
                .ownerService("ai-agent")
                .build();
        QuotaFreezeVO freeze = requireReservation(
                memberQuotaFeignClient.freeze(freezeRequest), "配额预扣失败");

        // The freeze response is intentionally compact. Authenticate the complete immutable
        // payload through the idempotency-query endpoint before any provider can start.
        RemoteReservationStatus status = findByRequestRemote(userId, normalizedRequestId, traceId);
        if (status.state() == QuotaBillingPort.ReservationState.NOT_FOUND
                || !freeze.getFreezeId().equals(status.freezeId())
                || freeze.getAmount() != status.reservedMicrocredits()) {
            throw new QuotaRemoteCallException("配额预扣响应与状态查询不一致");
        }
        return status;
    }

    @Override
    public RemoteReservationStatus confirmRemote(String freezeId, long actualMicrocredits) {
        return confirmRemote(freezeId, actualMicrocredits, null, null);
    }

    @Override
    public RemoteReservationStatus confirmRemote(String freezeId,
                                                 long actualMicrocredits,
                                                 String billingRequestId,
                                                 String traceId) {
        return toRemoteStatus(requireStatus(memberQuotaFeignClient.confirm(QuotaFreezeActionRequest.builder()
                .freezeId(freezeId)
                .actualAmount(actualMicrocredits)
                .requestId(billingRequestId)
                .traceId(traceId)
                .build()), "配额结算失败"));
    }

    @Override
    public RemoteReservationStatus releaseRemote(String freezeId) {
        return releaseRemote(freezeId, null, null);
    }

    @Override
    public RemoteReservationStatus releaseRemote(String freezeId, String billingRequestId, String traceId) {
        return toRemoteStatus(requireStatus(memberQuotaFeignClient.release(
                QuotaFreezeActionRequest.builder().freezeId(freezeId)
                        .requestId(billingRequestId).traceId(traceId).build()), "配额释放失败"));
    }

    @Override
    public RemoteReservationStatus findByRequestRemote(Long userId, String billingRequestId) {
        return findByRequestRemote(userId, billingRequestId, null);
    }

    @Override
    public RemoteReservationStatus findByRequestRemote(Long userId, String billingRequestId, String traceId) {
        String normalizedRequestId = QuotaBillingRequestId.normalize(billingRequestId);
        MemberQuotaResult<QuotaFreezeVO> result = memberQuotaFeignClient.findByRequest(
                userId, normalizedRequestId, traceId);
        requireSuccess(result, "配额预扣查询失败");
        if (result.getData() == null) {
            return notFound(null, userId, normalizedRequestId);
        }
        return toRemoteStatus(result.getData());
    }

    @Override
    public RemoteReservationStatus findByFreezeIdRemote(String freezeId) {
        return findByFreezeIdRemote(freezeId, null, null);
    }

    @Override
    public RemoteReservationStatus findByFreezeIdRemote(String freezeId,
                                                         String billingRequestId,
                                                         String traceId) {
        MemberQuotaResult<QuotaFreezeVO> result = memberQuotaFeignClient.findByFreezeId(
                freezeId, billingRequestId, traceId);
        requireSuccess(result, "配额冻结查询失败");
        if (result.getData() == null) {
            return notFound(freezeId, null, null);
        }
        return toRemoteStatus(result.getData());
    }

    private QuotaFreezeVO requireReservation(MemberQuotaResult<QuotaFreezeVO> result, String fallback) {
        requireSuccess(result, fallback);
        QuotaFreezeVO data = result.getData();
        if (data == null || data.getFreezeId() == null || data.getFreezeId().isBlank()
                || data.getAmount() == null || data.getAmount() <= 0L) {
            throw new QuotaRemoteCallException(fallback + "：响应缺少冻结标识或金额");
        }
        return data;
    }

    private QuotaFreezeVO requireStatus(MemberQuotaResult<QuotaFreezeVO> result, String fallback) {
        requireSuccess(result, fallback);
        QuotaFreezeVO data = result.getData();
        if (data == null || data.getFreezeId() == null || data.getFreezeId().isBlank()
                || data.getStatus() == null || data.getStatus().isBlank()) {
            throw new QuotaRemoteCallException(fallback + "：响应缺少冻结状态");
        }
        return data;
    }

    private RemoteReservationStatus toRemoteStatus(QuotaFreezeVO data) {
        return new RemoteReservationStatus(
                data.getFreezeId(),
                data.getUserId(),
                data.getAmount() == null ? 0L : data.getAmount(),
                data.getSettledAmount() == null ? 0L : data.getSettledAmount(),
                data.getRequestedAmount(),
                data.getMinAmount(),
                data.getAbilityCode(),
                QuotaBillingPort.ReservationState.resolve(data.getStatus()),
                data.getRequestId(),
                data.getRequestFingerprint(),
                data.getOwnerService());
    }

    private RemoteReservationStatus notFound(String freezeId, Long userId, String billingRequestId) {
        return new RemoteReservationStatus(
                freezeId, userId, 0L, 0L, null, null, null,
                QuotaBillingPort.ReservationState.NOT_FOUND,
                billingRequestId, null, null);
    }

    private void requireSuccess(MemberQuotaResult<?> result, String fallback) {
        if (result == null) {
            throw new QuotaRemoteCallException("配额服务无响应");
        }
        if (Integer.valueOf(QUOTA_INSUFFICIENT_CODE).equals(result.getCode())) {
            throw new QuotaInsufficientException(result.getMessage() == null ? fallback : result.getMessage());
        }
        if (!Integer.valueOf(SUCCESS_CODE).equals(result.getCode())) {
            throw new QuotaRemoteCallException(result.getMessage() == null ? fallback : result.getMessage());
        }
    }
}
