package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ShopCartEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.domain.order.service.OrderService;
import com.aigroup.paymall.types.exception.AppException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 掉单恢复回归：CREATE 态拼团单重试必须重新锁单，而不是退化为全价 NO_MARKET 单。
 * 根因是下单时 market_deduction_amount 恒写 0，旧代码用 "== null" 判定导致重新锁单分支永不生效。
 */
public class OrderServiceCreateRecoveryTest {

    private OrderService orderService;
    private IOrderRepository repository;
    private IProductPort port;

    @Before
    public void setUp() {
        repository = mock(IOrderRepository.class);
        port = mock(IProductPort.class);
        orderService = new OrderService(repository, port);
        // stub 预支付：关闭支付宝，doPrepayOrder 走本地 stub 分支，只回写 updateOrderPayInfo
        ReflectionTestUtils.setField(orderService, "alipayEnabled", false);
    }

    private ShopCartEntity groupBuyCart() {
        return ShopCartEntity.builder()
                .userId("u1")
                .productId("P100")
                .productCode("PRO_MONTH")
                .teamId("team-1")
                .activityId(1000L)
                .marketTypeVO(MarketTypeVO.GROUP_BUY_MARKET)
                .build();
    }

    private OrderEntity createStateGroupBuyOrder(BigDecimal deduction) {
        return OrderEntity.builder()
                .orderId("order-recover-001")
                .productName("Pro 会员月卡")
                .orderStatusVO(OrderStatusVO.CREATE)
                .marketType(MarketTypeVO.GROUP_BUY_MARKET.getCode())
                .totalAmount(new BigDecimal("100.00"))
                .payAmount(new BigDecimal("100.00"))
                .marketDeductionAmount(deduction)
                .build();
    }

    @Test
    public void createOrder_createStateGroupBuyRetry_relocksInsteadOfDegradingToFullPrice() throws Exception {
        ShopCartEntity cart = groupBuyCart();
        // 下单时 deduction 恒写 0：这正是旧 "== null" 判定失效、退化为全价的触发条件
        when(repository.queryUnPayOrder(any(ShopCartEntity.class)))
                .thenReturn(createStateGroupBuyOrder(BigDecimal.ZERO));
        when(port.lockMarketPayOrder(eq("u1"), eq("team-1"), eq(1000L), eq("P100"), eq("order-recover-001")))
                .thenReturn(MarketPayDiscountEntity.builder()
                        .originalPrice(new BigDecimal("100.00"))
                        .deductionPrice(new BigDecimal("30.00"))
                        .payPrice(new BigDecimal("70.00"))
                        .build());

        orderService.createOrder(cart);

        // 关键断言：必须重新锁单
        verify(port).lockMarketPayOrder("u1", "team-1", 1000L, "P100", "order-recover-001");

        // 回写的支付单必须是拼团价、拼团类型，而不是全价 NO_MARKET
        ArgumentCaptor<PayOrderEntity> captor = ArgumentCaptor.forClass(PayOrderEntity.class);
        verify(repository).updateOrderPayInfo(captor.capture());
        PayOrderEntity saved = captor.getValue();
        Assert.assertEquals(MarketTypeVO.GROUP_BUY_MARKET.getCode(), saved.getMarketType());
        Assert.assertEquals(0, new BigDecimal("70.00").compareTo(saved.getPayAmount()));
        Assert.assertEquals(0, new BigDecimal("30.00").compareTo(saved.getMarketDeductionAmount()));
    }

    @Test
    public void createOrder_createStateGroupBuyRetry_lockFails_abortsWithoutDegrading() {
        ShopCartEntity cart = groupBuyCart();
        when(repository.queryUnPayOrder(any(ShopCartEntity.class)))
                .thenReturn(createStateGroupBuyOrder(BigDecimal.ZERO));
        // 锁单失败返回 null，必须中断下单而不是继续生成全价单
        when(port.lockMarketPayOrder(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(null);

        Assert.assertThrows(AppException.class, () -> orderService.createOrder(cart));

        // 锁单失败后不得回写任何支付单（不生成全价 NO_MARKET 单）
        verify(repository, never()).updateOrderPayInfo(any(PayOrderEntity.class));
    }
}
