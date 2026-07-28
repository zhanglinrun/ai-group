package com.aigroup.paymall.domain.benefit.service;

import com.aigroup.paymall.domain.benefit.adapter.port.IBenefitEventPort;
import com.aigroup.paymall.domain.benefit.adapter.repository.IBenefitEventRepository;
import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.types.enums.OutboxEventType;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class BenefitEventService implements IBenefitEventService {

    private static final int PUBLISH_BATCH_SIZE = 100;

    private final IOrderRepository orderRepository;
    private final IBenefitEventRepository benefitEventRepository;
    private final IBenefitEventPort benefitEventPort;

    public BenefitEventService(IOrderRepository orderRepository,
                               IBenefitEventRepository benefitEventRepository,
                               IBenefitEventPort benefitEventPort) {
        this.orderRepository = orderRepository;
        this.benefitEventRepository = benefitEventRepository;
        this.benefitEventPort = benefitEventPort;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueCompletedOrderEvents(List<String> orderIds, Long bonusQuota) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        for (String orderId : orderIds) {
            OrderEntity order = requireOrder(orderId);
            // Both fulfillment and quota delivery are committed with the business
            // state transition. MQ publication is exclusively handled by the
            // independent outbox publisher after this transaction commits.
            enqueueEvent(order, OutboxEventType.ORDER_PAY_SUCCESS.name(), null);
            enqueueEvent(order, OutboxEventType.GROUP_BUY_COMPLETED.name(), bonusQuota);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueRevokedBenefitEvents(List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        for (String orderId : orderIds) {
            // 撤销不需要加赠额度：member 侧按发放时记录的额度原路扣回。
            enqueueEvent(requireOrder(orderId), OutboxEventType.GROUP_BUY_REVOKED.name(), null);
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int publishPendingEvents() {
        List<BenefitEventEntity> pending = benefitEventRepository.queryUnpublished(PUBLISH_BATCH_SIZE);
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (BenefitEventEntity entity : pending) {
            if (tryPublish(entity)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public List<BenefitEventEntity> queryPendingGrants(Date since, Long lastId, int pageSize) {
        int size = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        return benefitEventRepository.queryPendingGrants(OutboxEventType.GROUP_BUY_COMPLETED.name(), since, lastId, size);
    }

    private OrderEntity requireOrder(String orderId) {
        OrderEntity order = orderRepository.queryOrderByOrderId(orderId);
        if (order == null) {
            throw new IllegalStateException("outbox event order not found: " + orderId);
        }
        return order;
    }

    private BenefitEventEntity enqueueEvent(OrderEntity order, String eventType, Long bonusQuota) {
        BenefitEventEntity existing = benefitEventRepository.findByOrderIdAndEventType(
                order.getOrderId(), eventType);
        if (existing != null) {
            return existing;
        }
        String productCode = StringUtils.isNotBlank(order.getProductCode())
                ? order.getProductCode()
                : order.getProductId();
        BenefitEventEntity entity = BenefitEventEntity.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .userId(parseUserId(order.getUserId()))
                .orderId(order.getOrderId())
                .productCode(productCode)
                .eventPublished(false)
                .baseQuota(order.getBaseQuotaSnapshot())
                .bonusQuota(bonusQuota)
                .build();
        try {
            benefitEventRepository.insert(entity);
            log.info("enqueued outbox event orderId={} eventId={} eventType={}",
                    entity.getOrderId(), entity.getEventId(), entity.getEventType());
            return entity;
        } catch (RuntimeException insertFailure) {
            // Concurrent duplicate callbacks are safe because order_id + event_type
            // is unique. Do not swallow a real database outage: only accept the
            // exception when the winning row can be read back.
            BenefitEventEntity concurrent = benefitEventRepository.findByOrderIdAndEventType(
                    order.getOrderId(), eventType);
            if (concurrent == null) {
                throw insertFailure;
            }
            return concurrent;
        }
    }

    private boolean tryPublish(BenefitEventEntity entity) {
        if (Boolean.TRUE.equals(entity.getEventPublished())) {
            return false;
        }
        OutboxEventType eventType = OutboxEventType.valueOf(entity.getEventType());
        if (OutboxEventType.GROUP_BUY_COMPLETED.equals(eventType) && hasUnpublishedRevoke(entity)) {
            log.info("defer completed outbox event until revoke tombstone is published orderId={} eventId={}",
                    entity.getOrderId(), entity.getEventId());
            return false;
        }
        if (OutboxEventType.ORDER_PAY_SUCCESS.equals(eventType)) {
            benefitEventPort.publishOrderPaySuccess(
                    entity.getEventId(), entity.getUserId(), entity.getOrderId());
        } else {
            benefitEventPort.publishTradeCompleted(toTradeCompletedEvent(entity));
        }
        benefitEventRepository.markPublished(entity.getEventId());
        log.info("published outbox event orderId={} eventId={} eventType={}",
                entity.getOrderId(), entity.getEventId(), entity.getEventType());
        return true;
    }

    private boolean hasUnpublishedRevoke(BenefitEventEntity completed) {
        BenefitEventEntity revoke = benefitEventRepository.findByOrderIdAndEventType(
                completed.getOrderId(), OutboxEventType.GROUP_BUY_REVOKED.name());
        return revoke != null && !Boolean.TRUE.equals(revoke.getEventPublished());
    }

    private TradeCompletedEvent toTradeCompletedEvent(BenefitEventEntity entity) {
        return TradeCompletedEvent.builder()
                .eventId(entity.getEventId())
                .eventType(entity.getEventType())
                .userId(entity.getUserId())
                .orderId(entity.getOrderId())
                .productCode(entity.getProductCode())
                .baseQuota(entity.getBaseQuota())
                .bonusQuota(entity.getBonusQuota())
                .build();
    }

    private Long parseUserId(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId is blank");
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("userId is not numeric: " + userId, ex);
        }
    }

}
