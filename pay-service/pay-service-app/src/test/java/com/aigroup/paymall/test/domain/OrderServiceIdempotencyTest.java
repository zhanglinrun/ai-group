package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.domain.order.model.entity.ShopCartEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderCreateStage;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.domain.order.service.OrderRequestFingerprint;
import com.aigroup.paymall.domain.order.service.OrderService;
import com.aigroup.paymall.types.enums.ResponseCode;
import com.aigroup.paymall.types.exception.AppException;
import com.alipay.api.AlipayApiException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OrderServiceIdempotencyTest {

    private OrderService orderService;
    private IOrderRepository repository;
    private IProductPort port;

    @Before
    public void setUp() {
        repository = mock(IOrderRepository.class);
        port = mock(IProductPort.class);
        orderService = new OrderService(repository, port);
        ReflectionTestUtils.setField(orderService, "alipayEnabled", false);
        when(port.queryProductByProductId("P100")).thenReturn(quotaProduct());
        when(repository.markGroupLocked(anyString(), anyString(), any(), any(), any())).thenReturn(true);
        when(repository.markProviderStarted(anyString(), anyString())).thenReturn(true);
        when(repository.completeOrderPrepay(any(PayOrderEntity.class), anyString())).thenReturn(true);
    }

    @Test
    public void insertOwnerAloneMayLockGroupAndCreateProviderOrder() throws Exception {
        when(port.lockMarketPayOrder(eq("u1"), eq("team-1"), eq(1000L), eq("P100"),
                any(String.class), eq(new BigDecimal("12.00"))))
                .thenReturn(groupDiscount());

        PayOrderEntity result = orderService.createOrder(groupBuyCart());

        ArgumentCaptor<CreateOrderAggregate> aggregate = ArgumentCaptor.forClass(CreateOrderAggregate.class);
        verify(repository).saveOrderIfAbsent(aggregate.capture());
        OrderEntity inserted = aggregate.getValue().getOrderEntity();
        verify(port).lockMarketPayOrder("u1", "team-1", 1000L, "P100", inserted.getOrderId(),
                new BigDecimal("12.00"));
        verify(repository).markGroupLocked(eq(inserted.getOrderId()), eq(inserted.getCreateOwnerToken()),
                eq(1), eq(new BigDecimal("2.00")), eq(new BigDecimal("10.00")));
        verify(repository).markProviderStarted(inserted.getOrderId(), inserted.getCreateOwnerToken());
        verify(repository).completeOrderPrepay(any(PayOrderEntity.class), eq(inserted.getCreateOwnerToken()));
        Assert.assertEquals(inserted.getOrderId(), result.getOrderId());
        Assert.assertFalse(result.isIdempotentReplay());
    }

    @Test
    public void sameRequestAndPayloadReplaysCompletedOrderWithoutExternalSideEffects() throws Exception {
        ShopCartEntity cart = groupBuyCart();
        OrderEntity existing = completedOrder(cart, "order-winner");
        when(repository.queryOrderByClientRequestId("u1", "pay-request-1")).thenReturn(existing);

        PayOrderEntity result = orderService.createOrder(cart);

        Assert.assertEquals("order-winner", result.getOrderId());
        Assert.assertEquals("https://qr.alipay.example/winner", result.getPayUrl());
        Assert.assertTrue(result.isIdempotentReplay());
        verify(repository, never()).saveOrderIfAbsent(any(CreateOrderAggregate.class));
        verify(repository, never()).claimOrderCreation(anyString(), anyString());
        verify(port, never()).lockMarketPayOrder(any(), any(), any(), any(), any(), any());
    }

    @Test
    public void concurrentInsertLoserCannotStealActiveLeaseOrRepeatExternalCalls() {
        ShopCartEntity cart = groupBuyCart();
        OrderEntity winner = resumableOrder(cart, "order-race-winner", OrderCreateStage.LOCAL_CREATED);
        when(repository.queryOrderByClientRequestId("u1", "pay-request-1")).thenReturn(null);
        when(repository.saveOrderIfAbsent(any(CreateOrderAggregate.class))).thenReturn(winner);
        when(repository.claimOrderCreation(eq("order-race-winner"), anyString())).thenReturn(false);

        AppException error = Assert.assertThrows(AppException.class, () -> orderService.createOrder(cart));

        Assert.assertEquals(ResponseCode.ORDER_CREATION_IN_PROGRESS.getCode(), error.getCode());
        verify(port, never()).lockMarketPayOrder(any(), any(), any(), any(), any(), any());
        verify(repository, never()).markProviderStarted(anyString(), anyString());
    }

    @Test
    public void retryAfterInsertThenLockFailureClaimsLeaseAndResumes() throws Exception {
        AtomicReference<OrderEntity> inserted = captureInsertedOrder();
        when(repository.claimOrderCreation(anyString(), anyString())).thenReturn(true);
        when(port.lockMarketPayOrder(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("group temporarily unavailable"))
                .thenReturn(groupDiscount());

        Assert.assertThrows(IllegalStateException.class, () -> orderService.createOrder(groupBuyCart()));
        preparePersistedCreate(inserted.get(), OrderCreateStage.LOCAL_CREATED);

        PayOrderEntity recovered = orderService.createOrder(groupBuyCart());

        Assert.assertEquals(inserted.get().getOrderId(), recovered.getOrderId());
        verify(port, times(2)).lockMarketPayOrder(any(), any(), any(), any(), any(), any());
        verify(repository, atLeastOnce()).releaseOrderCreationClaim(eq(inserted.get().getOrderId()), anyString());
        verify(repository).completeOrderPrepay(any(PayOrderEntity.class), anyString());
    }

    @Test
    public void retryAfterGroupLockCommitResponseLossContinuesFromGroupLockedWithoutRelocking() throws Exception {
        AtomicReference<OrderEntity> inserted = captureInsertedOrder();
        when(repository.claimOrderCreation(anyString(), anyString())).thenReturn(true);
        when(port.lockMarketPayOrder(any(), any(), any(), any(), any(), any())).thenReturn(groupDiscount());
        AtomicBoolean firstMark = new AtomicBoolean(true);
        when(repository.markGroupLocked(anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            if (firstMark.getAndSet(false)) {
                OrderEntity persisted = inserted.get();
                persisted.setCreateStage(OrderCreateStage.GROUP_LOCKED);
                persisted.setMarketType(MarketTypeVO.GROUP_BUY_MARKET.getCode());
                persisted.setMarketDeductionAmount(new BigDecimal("2.00"));
                persisted.setPayAmount(new BigDecimal("10.00"));
                throw new IllegalStateException("database acknowledgement lost");
            }
            return true;
        });

        Assert.assertThrows(IllegalStateException.class, () -> orderService.createOrder(groupBuyCart()));
        preparePersistedCreate(inserted.get(), OrderCreateStage.GROUP_LOCKED);

        PayOrderEntity recovered = orderService.createOrder(groupBuyCart());

        Assert.assertEquals(inserted.get().getOrderId(), recovered.getOrderId());
        verify(port, times(1)).lockMarketPayOrder(any(), any(), any(), any(), any(), any());
        verify(repository).completeOrderPrepay(any(PayOrderEntity.class), anyString());
    }

    @Test
    public void providerStartedWithoutDurableResultFailsClosedForManualReview() {
        ShopCartEntity cart = groupBuyCart();
        OrderEntity existing = resumableOrder(cart, "order-provider-uncertain", OrderCreateStage.PROVIDER_STARTED);
        when(repository.queryOrderByClientRequestId("u1", "pay-request-1")).thenReturn(existing);

        AppException error = Assert.assertThrows(AppException.class, () -> orderService.createOrder(cart));

        Assert.assertEquals(ResponseCode.ORDER_CREATION_REVIEW.getCode(), error.getCode());
        verify(repository, never()).claimOrderCreation(anyString(), anyString());
        verify(port, never()).lockMarketPayOrder(any(), any(), any(), any(), any(), any());
    }

    @Test
    public void providerFailureAfterStartedIsPersistedAsManualReviewInsteadOfBlindRetry() {
        OrderService failingProviderService = new OrderService(repository, port) {
            @Override
            protected PayOrderEntity doPrepayOrder(String userId, String productId, String productName,
                                                   String orderId, BigDecimal totalAmount,
                                                   MarketPayDiscountEntity marketPayDiscountEntity)
                    throws AlipayApiException {
                throw new AlipayApiException("provider response lost");
            }
        };
        when(port.lockMarketPayOrder(any(), any(), any(), any(), any(), any())).thenReturn(groupDiscount());

        AppException error = Assert.assertThrows(AppException.class,
                () -> failingProviderService.createOrder(groupBuyCart()));

        Assert.assertEquals(ResponseCode.ORDER_CREATION_REVIEW.getCode(), error.getCode());
        verify(repository).markProviderStarted(anyString(), anyString());
        verify(repository, atLeastOnce()).markOrderCreationManualReview(anyString(), anyString());
        verify(repository, never()).completeOrderPrepay(any(PayOrderEntity.class), anyString());
    }

    @Test
    public void sameRequestWithDifferentRouteIsExplicitConflict() {
        ShopCartEntity cart = groupBuyCart();
        OrderEntity existing = completedOrder(cart, "order-winner");
        existing.setRequestFingerprint("different-payload-fingerprint");
        when(repository.queryOrderByClientRequestId("u1", "pay-request-1")).thenReturn(existing);

        AppException error = Assert.assertThrows(AppException.class, () -> orderService.createOrder(cart));

        Assert.assertEquals(ResponseCode.REQUEST_CONFLICT.getCode(), error.getCode());
        verify(repository, never()).saveOrderIfAbsent(any(CreateOrderAggregate.class));
        verify(port, never()).lockMarketPayOrder(any(), any(), any(), any(), any(), any());
    }

    @Test
    public void fingerprintSeparatesDirectOpenGroupAndJoinGroupPaths() {
        ShopCartEntity direct = directCart();
        ShopCartEntity openGroup = groupBuyCart();
        openGroup.setTeamId(null);
        ShopCartEntity joinGroup = groupBuyCart();

        Assert.assertNotEquals(OrderRequestFingerprint.calculate(direct),
                OrderRequestFingerprint.calculate(openGroup));
        Assert.assertNotEquals(OrderRequestFingerprint.calculate(openGroup),
                OrderRequestFingerprint.calculate(joinGroup));
    }

    private AtomicReference<OrderEntity> captureInsertedOrder() {
        AtomicReference<OrderEntity> inserted = new AtomicReference<>();
        when(repository.queryOrderByClientRequestId("u1", "pay-request-1"))
                .thenAnswer(invocation -> inserted.get());
        when(repository.saveOrderIfAbsent(any(CreateOrderAggregate.class))).thenAnswer(invocation -> {
            CreateOrderAggregate aggregate = invocation.getArgument(0);
            inserted.set(aggregate.getOrderEntity());
            return null;
        });
        return inserted;
    }

    private void preparePersistedCreate(OrderEntity order, OrderCreateStage stage) {
        order.setUserId("u1");
        order.setOrderStatusVO(OrderStatusVO.CREATE);
        order.setCreateStage(stage);
        order.setCreateOwnerToken(null);
    }

    private ShopCartEntity groupBuyCart() {
        return ShopCartEntity.builder()
                .requestId("pay-request-1")
                .userId(" u1 ")
                .productId(" P100 ")
                .productCode(" QUOTA_LIGHT ")
                .teamId(" team-1 ")
                .activityId(1000L)
                .marketTypeVO(MarketTypeVO.GROUP_BUY_MARKET)
                .build();
    }

    private ShopCartEntity directCart() {
        return ShopCartEntity.builder()
                .requestId("pay-request-1")
                .userId("u1")
                .productId("P100")
                .productCode("QUOTA_LIGHT")
                .marketTypeVO(MarketTypeVO.NO_MARKET)
                .build();
    }

    private OrderEntity completedOrder(ShopCartEntity rawCart, String orderId) {
        OrderEntity order = resumableOrder(rawCart, orderId, OrderCreateStage.PREPAY_READY);
        order.setOrderStatusVO(OrderStatusVO.PAY_WAIT);
        order.setPayUrl("https://qr.alipay.example/winner");
        return order;
    }

    private OrderEntity resumableOrder(ShopCartEntity rawCart, String orderId, OrderCreateStage stage) {
        ShopCartEntity normalized = normalizedCart(rawCart);
        return OrderEntity.builder()
                .clientRequestId(normalized.getRequestId())
                .requestFingerprint(OrderRequestFingerprint.calculate(normalized))
                .createStage(stage)
                .userId(normalized.getUserId())
                .productId(normalized.getProductId())
                .productCode(normalized.getProductCode())
                .productName("轻量额度包")
                .baseQuotaSnapshot(60L)
                .orderId(orderId)
                .orderStatusVO(OrderStatusVO.CREATE)
                .totalAmount(new BigDecimal("12.00"))
                .marketType(normalized.getMarketTypeVO().getCode())
                .groupActivityId(normalized.getActivityId())
                .groupTeamId(normalized.getTeamId())
                .marketDeductionAmount(new BigDecimal("2.00"))
                .payAmount(new BigDecimal("10.00"))
                .build();
    }

    private ShopCartEntity normalizedCart(ShopCartEntity rawCart) {
        return ShopCartEntity.builder()
                .requestId(rawCart.getRequestId().trim())
                .userId(rawCart.getUserId().trim())
                .productId(rawCart.getProductId().trim())
                .productCode(rawCart.getProductCode().trim())
                .teamId(rawCart.getTeamId() == null ? null : rawCart.getTeamId().trim())
                .activityId(rawCart.getActivityId())
                .marketTypeVO(rawCart.getMarketTypeVO())
                .build();
    }

    private ProductEntity quotaProduct() {
        return ProductEntity.builder()
                .productId("P100")
                .productCode("QUOTA_LIGHT")
                .productName("轻量额度包")
                .price(new BigDecimal("12.00"))
                .baseQuota(60L)
                .build();
    }

    private MarketPayDiscountEntity groupDiscount() {
        return MarketPayDiscountEntity.builder()
                .originalPrice(new BigDecimal("12.00"))
                .deductionPrice(new BigDecimal("2.00"))
                .payPrice(new BigDecimal("10.00"))
                .build();
    }
}
