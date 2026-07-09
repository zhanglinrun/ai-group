package com.aigroup.paymall.domain.benefit.service;

import com.aigroup.paymall.domain.benefit.adapter.port.IBenefitEventPort;
import com.aigroup.paymall.domain.benefit.adapter.repository.IBenefitEventRepository;
import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.types.enums.BenefitEventType;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class BenefitEventService implements IBenefitEventService {

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
    public void publishGroupBuyCompletedEvents(List<String> orderIds) {
        publishEvents(orderIds, BenefitEventType.GROUP_BUY_COMPLETED.name(), false);
    }

    @Override
    public void publishGroupBuyRevokedEvents(List<String> orderIds) {
        publishEvents(orderIds, BenefitEventType.GROUP_BUY_REVOKED.name(), true);
    }

    @Override
    public int republishPendingEvents() {
        int completed = republishPending(BenefitEventType.GROUP_BUY_COMPLETED.name());
        int revoked = republishPending(BenefitEventType.GROUP_BUY_REVOKED.name());
        return completed + revoked;
    }

    @Override
    public List<BenefitEventEntity> queryPendingGrants(Date since, Long lastId, int pageSize) {
        int size = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        return benefitEventRepository.queryPendingGrants(BenefitEventType.GROUP_BUY_COMPLETED.name(), since, lastId, size);
    }

    private void publishEvents(List<String> orderIds, String eventType, boolean requireCompletedPublished) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        for (String orderId : orderIds) {
            try {
                publishEvent(orderId, eventType, requireCompletedPublished);
            } catch (Exception e) {
                log.error("publish benefit event failed orderId={} eventType={}", orderId, eventType, e);
            }
        }
    }

    private int republishPending(String eventType) {
        List<BenefitEventEntity> pending = benefitEventRepository.queryUnpublished(eventType, 50);
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

    private void publishEvent(String orderId, String eventType, boolean requireCompletedPublished) {
        OrderEntity order = orderRepository.queryOrderByOrderId(orderId);
        if (order == null) {
            log.warn("skip benefit event, order not found orderId={}", orderId);
            return;
        }
        if (!MarketTypeVO.GROUP_BUY_MARKET.getCode().equals(order.getMarketType())) {
            log.info("skip benefit event, not group buy order orderId={}", orderId);
            return;
        }
        if (requireCompletedPublished) {
            BenefitEventEntity completed = benefitEventRepository.findByOrderIdAndEventType(
                    orderId, BenefitEventType.GROUP_BUY_COMPLETED.name());
            if (completed == null || !Boolean.TRUE.equals(completed.getEventPublished())) {
                // keep the revoke intent: persist an unpublished placeholder (event_published=false)
                // so BenefitEventRepublishJob can eventually publish it, instead of dropping it
                BenefitEventEntity pendingRevoke = benefitEventRepository.findByOrderIdAndEventType(orderId, eventType);
                if (pendingRevoke == null) {
                    try {
                        createBenefitEvent(order, eventType);
                    } catch (Exception e) {
                        // tolerate duplicate insert on uk_order_event_type (concurrent revoke)
                        log.warn("create pending revoke event failed (may already exist) orderId={}", orderId, e);
                    }
                }
                log.info("defer revoke event, completed benefit not published yet orderId={}", orderId);
                return;
            }
        }

        BenefitEventEntity existing = benefitEventRepository.findByOrderIdAndEventType(orderId, eventType);
        if (existing != null && Boolean.TRUE.equals(existing.getEventPublished())) {
            log.info("skip benefit event, already published orderId={} eventType={}", orderId, eventType);
            return;
        }

        BenefitEventEntity entity = existing != null ? existing : createBenefitEvent(order, eventType);
        tryPublish(entity);
    }

    private BenefitEventEntity createBenefitEvent(OrderEntity order, String eventType) {
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
                .build();
        benefitEventRepository.insert(entity);
        return entity;
    }

    private boolean tryPublish(BenefitEventEntity entity) {
        if (Boolean.TRUE.equals(entity.getEventPublished())) {
            return false;
        }
        TradeCompletedEvent event = TradeCompletedEvent.builder()
                .eventId(entity.getEventId())
                .eventType(entity.getEventType())
                .userId(entity.getUserId())
                .orderId(entity.getOrderId())
                .productCode(entity.getProductCode())
                .build();
        try {
            benefitEventPort.publishTradeCompleted(event);
            benefitEventRepository.markPublished(entity.getEventId());
            log.info("published benefit event orderId={} eventId={}", entity.getOrderId(), entity.getEventId());
            return true;
        } catch (Exception e) {
            log.error("failed to publish benefit event orderId={} eventId={}", entity.getOrderId(), entity.getEventId(), e);
            return false;
        }
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
