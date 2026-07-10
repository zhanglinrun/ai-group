package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.service.OrderService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the "unpaid order gets benefits" defect: the team_success callback
 * may carry a mix of orders, but only orders that actually transitioned
 * PAY_SUCCESS -> MARKET may be granted benefits. OrderService must grant benefits
 * strictly for the settled subset returned by the repository, never for the raw
 * callback list (which could include PAY_WAIT / CLOSE orders).
 * <p>
 * Offline test: repository / port / benefit service are mocked, no MySQL/MQ required.
 */
public class OrderServiceMarketSettlementTest {

    private OrderService orderService;
    private IOrderRepository repository;
    private IBenefitEventService benefitEventService;

    @Before
    public void setUp() {
        repository = mock(IOrderRepository.class);
        IProductPort port = mock(IProductPort.class);
        benefitEventService = mock(IBenefitEventService.class);
        orderService = new OrderService(repository, port);
        ReflectionTestUtils.setField(orderService, "benefitEventService", benefitEventService);
    }

    @Test
    public void changeOrderMarketSettlement_grantsBenefitOnlyForSettledOrders() {
        List<String> callbackList = Arrays.asList("order-paid", "order-unpaid");
        // repository reports only the genuinely settled (now MARKET) order
        when(repository.changeOrderMarketSettlement(callbackList))
                .thenReturn(Collections.singletonList("order-paid"));

        orderService.changeOrderMarketSettlement(callbackList);

        // benefit granted for the settled order only, not the unpaid one
        verify(benefitEventService).publishGroupBuyCompletedEvents(Collections.singletonList("order-paid"));
    }

    @Test
    public void changeOrderMarketSettlement_noSettledOrders_grantsNothing() {
        List<String> callbackList = Collections.singletonList("order-unpaid");
        when(repository.changeOrderMarketSettlement(callbackList))
                .thenReturn(Collections.emptyList());

        orderService.changeOrderMarketSettlement(callbackList);

        // nothing settled -> no benefit event at all (no free membership for unpaid orders)
        verify(benefitEventService, never()).publishGroupBuyCompletedEvents(anyList());
    }
}
