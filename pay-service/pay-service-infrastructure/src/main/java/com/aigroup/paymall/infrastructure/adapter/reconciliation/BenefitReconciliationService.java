package com.aigroup.paymall.infrastructure.adapter.reconciliation;

import com.aigroup.paymall.domain.benefit.adapter.port.IBenefitEventPort;
import com.aigroup.paymall.domain.benefit.service.IBenefitReconciliationService;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.infrastructure.dao.IBenefitEventDao;
import com.aigroup.paymall.infrastructure.dao.IBenefitReconciliationDao;
import com.aigroup.paymall.infrastructure.dao.po.BenefitEvent;
import com.aigroup.paymall.infrastructure.dao.po.BenefitReconciliation;
import com.aigroup.paymall.infrastructure.gateway.IMemberCatalogService;
import com.aigroup.paymall.infrastructure.gateway.dto.MemberBenefitStatusDTO;
import com.aigroup.paymall.infrastructure.gateway.response.MemberResult;
import com.aigroup.paymall.types.enums.OutboxEventType;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

@Slf4j
@Service
public class BenefitReconciliationService implements IBenefitReconciliationService {
    private static final int BATCH_SIZE = 50;
    private static final int MIN_AGE_MINUTES = 10;
    private static final int MAX_REPLAYS = 3;

    private final IBenefitEventDao events;
    private final IBenefitReconciliationDao outcomes;
    private final IOrderRepository orders;
    private final IMemberCatalogService member;
    private final IBenefitEventPort publisher;

    public BenefitReconciliationService(IBenefitEventDao events, IBenefitReconciliationDao outcomes,
                                        IOrderRepository orders, IMemberCatalogService member,
                                        IBenefitEventPort publisher) {
        this.events = events;
        this.outcomes = outcomes;
        this.orders = orders;
        this.member = member;
        this.publisher = publisher;
    }

    @Override
    public int reconcilePublishedGrants() {
        Date cutoff = new Date(System.currentTimeMillis() - MIN_AGE_MINUTES * 60_000L);
        List<BenefitEvent> due = events.queryDuePublishedGrants(cutoff, BATCH_SIZE);
        int claimed = 0;
        for (BenefitEvent event : due) {
            outcomes.insertIfAbsent(event.getEventId());
            String token = UUID.randomUUID().toString();
            if (outcomes.claim(event.getEventId(), token) != 1) {
                continue;
            }
            claimed++;
            try {
                reconcile(event, token);
            } catch (RuntimeException ex) {
                // A failed DB read/write retains PROCESSING until its lease expires.
                log.error("benefit reconciliation failed eventId={} orderId={}",
                        event.getEventId(), event.getOrderId(), ex);
            }
        }
        return claimed;
    }

    private void reconcile(BenefitEvent event, String token) {
        BenefitReconciliation previous = outcomes.findByEventId(event.getEventId());
        OrderEntity order = orders.queryOrderByOrderId(event.getOrderId());
        String orderStatus = order == null || order.getOrderStatusVO() == null
                ? null : order.getOrderStatusVO().name();
        BenefitEvent revoke = events.findByOrderIdAndEventType(event.getOrderId(),
                OutboxEventType.GROUP_BUY_REVOKED.name());

        String memberStatus;
        MemberBenefitStatusDTO memberDetails;
        try {
            MemberResult<MemberBenefitStatusDTO> response = member.queryBenefitOrderStatus(event.getOrderId());
            if (response == null || response.getCode() == null || response.getCode() >= 500) {
                finish(event, token, "UNAVAILABLE", orderStatus, null, "MEMBER_APPLICATION_UNAVAILABLE", 5);
                return;
            }
            if (!Integer.valueOf(200).equals(response.getCode())
                    || response.getData() == null || !validStatus(response.getData().getStatus())) {
                finish(event, token, "MANUAL", orderStatus, null, "INVALID_MEMBER_RESPONSE", null);
                return;
            }
            memberDetails = response.getData();
            memberStatus = memberDetails.getStatus();
        } catch (RuntimeException ex) {
            log.warn("member benefit status unavailable orderId={}", event.getOrderId(), ex);
            finish(event, token, "UNAVAILABLE", orderStatus, null, "MEMBER_UNAVAILABLE", 5);
            return;
        }

        if (order == null || orderStatus == null) {
            finish(event, token, "MANUAL", orderStatus, memberStatus, "ORDER_MISSING", null);
        } else if (revoke != null) {
            if (!eligible(order.getOrderStatusVO()) && "REVOKED".equals(memberStatus)) {
                finish(event, token, "REVOKED", orderStatus, memberStatus, "REVOKE_APPLIED", null);
            } else if (!eligible(order.getOrderStatusVO()) && "PENDING".equals(memberStatus)
                    && !Boolean.TRUE.equals(revoke.getEventPublished())) {
                finish(event, token, "UNAVAILABLE", orderStatus, memberStatus, "REVOKE_OUTBOX_PENDING", 5);
            } else {
                finish(event, token, "MANUAL", orderStatus, memberStatus, "REVOKE_EVENT_CONFLICT", null);
            }
        } else if (!eligible(order.getOrderStatusVO())) {
            finish(event, token, "MANUAL", orderStatus, memberStatus, "INELIGIBLE_ORDER", null);
        } else if ("GRANTED".equals(memberStatus)) {
            if (Boolean.TRUE.equals(memberDetails.getManualReview())) {
                finish(event, token, "MANUAL", orderStatus, memberStatus, "MEMBER_MANUAL_REVIEW", null);
            } else if (!grantMatches(memberDetails, event, order)) {
                finish(event, token, "MANUAL", orderStatus, memberStatus, "GRANT_MISMATCH", null);
            } else {
                finish(event, token, "GRANTED", orderStatus, memberStatus, "CONFIRMED", null);
            }
        } else if ("REVOKED".equals(memberStatus)) {
            finish(event, token, "MANUAL", orderStatus, memberStatus, "REVOKED_ELIGIBLE_ORDER", null);
        } else if (previous == null || previous.getReplayAttempts() >= MAX_REPLAYS) {
            finish(event, token, "MANUAL", orderStatus, memberStatus, "REPLAY_LIMIT", null);
        } else if (!validPayload(event, order)) {
            finish(event, token, "MANUAL", orderStatus, memberStatus, "INVALID_OUTBOX_PAYLOAD", null);
        } else {
            replay(event, token, previous.getReplayAttempts());
        }
    }

    private void replay(BenefitEvent event, String token, int attempts) {
        OrderEntity current = orders.queryOrderByOrderId(event.getOrderId());
        if (current == null || !eligible(current.getOrderStatusVO())
                || events.findByOrderIdAndEventType(event.getOrderId(),
                    OutboxEventType.GROUP_BUY_REVOKED.name()) != null) {
            finish(event, token, "MANUAL", current == null || current.getOrderStatusVO() == null
                    ? null : current.getOrderStatusVO().name(), "PENDING", "CHANGED_BEFORE_REPLAY", null);
            return;
        }
        if (!validPayload(event, current)) {
            finish(event, token, "MANUAL", current.getOrderStatusVO().name(), "PENDING", "ORDER_PAYLOAD_CHANGED", null);
            return;
        }
        if (outcomes.recordReplayIntent(event.getEventId(), token, MAX_REPLAYS) != 1) {
            return;
        }
        try {
            publisher.publishTradeCompleted(TradeCompletedEvent.builder()
                    .eventId(event.getEventId())
                    .eventType(event.getEventType())
                    .userId(event.getUserId())
                    .orderId(event.getOrderId())
                    .productCode(event.getProductCode())
                    .baseQuota(event.getBaseQuota())
                    .build());
        } catch (RuntimeException ex) {
            log.warn("benefit replay failed eventId={}", event.getEventId(), ex);
            finish(event, token, "UNAVAILABLE", current.getOrderStatusVO().name(), "PENDING",
                    "PUBLISH_FAILED", backoffMinutes(attempts + 1));
            return;
        }
        finish(event, token, "REPLAYED", current.getOrderStatusVO().name(), "PENDING",
                "AWAITING_MEMBER", backoffMinutes(attempts + 1));
    }

    private void finish(BenefitEvent event, String token, String outcome, String orderStatus,
                        String memberStatus, String detail, Integer delayMinutes) {
        if (outcomes.finish(event.getEventId(), token, outcome, orderStatus, memberStatus, detail, delayMinutes) != 1) {
            log.warn("benefit reconciliation claim lost eventId={} outcome={}", event.getEventId(), outcome);
        } else if ("MANUAL".equals(outcome)) {
            log.warn("benefit reconciliation requires manual review eventId={} orderId={} orderStatus={} memberStatus={} detail={}",
                    event.getEventId(), event.getOrderId(), orderStatus, memberStatus, detail);
        }
    }

    private static boolean validPayload(BenefitEvent event, OrderEntity order) {
        String sku = order.getProductCode();
        if (sku == null || sku.isBlank()) sku = order.getProductId();
        return event.getUserId() != null && String.valueOf(event.getUserId()).equals(order.getUserId())
                && event.getBaseQuota() != null && event.getBaseQuota() > 0
                && Objects.equals(event.getBaseQuota(), order.getBaseQuotaSnapshot())
                && sku != null && !sku.isBlank() && sku.equals(event.getProductCode());
    }

    private static boolean grantMatches(MemberBenefitStatusDTO details, BenefitEvent event, OrderEntity order) {
        if (!validPayload(event, order)) return false;
        try {
            long grantedMicro = Math.multiplyExact(event.getBaseQuota(), 1_000_000L);
            return String.valueOf(event.getUserId()).equals(details.getUserId())
                    && event.getProductCode().equals(details.getProductCode())
                    && Long.toString(grantedMicro).equals(details.getGrantedQuotaMicro());
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static boolean validStatus(String status) {
        return "PENDING".equals(status) || "GRANTED".equals(status) || "REVOKED".equals(status);
    }

    private static boolean eligible(OrderStatusVO status) {
        return status == OrderStatusVO.MARKET || status == OrderStatusVO.DEAL_DONE;
    }

    private static int backoffMinutes(int attempt) {
        return attempt == 1 ? 5 : attempt == 2 ? 15 : 45;
    }
}
