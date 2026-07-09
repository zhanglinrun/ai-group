package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.job.TimeoutCloseOrderJob;
import com.aigroup.paymall.trigger.job.support.AlipayOrderReconcileSupport;
import com.alipay.api.AlipayApiException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C1: the timeout-close job must reconcile against alipay before closing -
 * a paid order is recovered instead of closed, and an unconfirmed alipay-side
 * close keeps the local order open for the next run.
 */
public class TimeoutCloseOrderJobTest {

    private TimeoutCloseOrderJob job;
    private IOrderService orderService;
    private AlipayOrderReconcileSupport reconcileSupport;

    @Before
    public void setUp() {
        orderService = mock(IOrderService.class);
        reconcileSupport = mock(AlipayOrderReconcileSupport.class);
        job = new TimeoutCloseOrderJob();
        ReflectionTestUtils.setField(job, "orderService", orderService);
        ReflectionTestUtils.setField(job, "alipayOrderReconcileSupport", reconcileSupport);
    }

    @Test
    public void exec_paidOrderIsRecoveredNotClosed() throws Exception {
        when(orderService.queryTimeoutCloseOrderList()).thenReturn(Collections.singletonList("order-001"));
        when(reconcileSupport.recoverIfPaidOnAlipay("order-001")).thenReturn(true);

        job.exec();

        verify(orderService, never()).changeOrderClose("order-001");
        verify(reconcileSupport, never()).closeAlipayTrade("order-001");
    }

    @Test
    public void exec_unpaidOrderClosesAlipayTradeThenLocalOrder() throws Exception {
        when(orderService.queryTimeoutCloseOrderList()).thenReturn(Collections.singletonList("order-002"));
        when(reconcileSupport.recoverIfPaidOnAlipay("order-002")).thenReturn(false);
        when(reconcileSupport.closeAlipayTrade("order-002")).thenReturn(true);

        job.exec();

        verify(reconcileSupport).closeAlipayTrade("order-002");
        verify(orderService).changeOrderClose("order-002");
    }

    @Test
    public void exec_unconfirmedAlipayCloseSkipsLocalClose() throws Exception {
        when(orderService.queryTimeoutCloseOrderList()).thenReturn(Collections.singletonList("order-003"));
        when(reconcileSupport.recoverIfPaidOnAlipay("order-003")).thenReturn(false);
        when(reconcileSupport.closeAlipayTrade("order-003")).thenReturn(false);

        job.exec();

        verify(orderService, never()).changeOrderClose("order-003");
    }

    @Test
    public void exec_reconcileFailureKeepsOrderOpen() throws Exception {
        when(orderService.queryTimeoutCloseOrderList()).thenReturn(Collections.singletonList("order-004"));
        when(reconcileSupport.recoverIfPaidOnAlipay("order-004"))
                .thenThrow(new AlipayApiException("alipay unreachable"));

        job.exec();

        // payment state unknown: never close blindly
        verify(orderService, never()).changeOrderClose("order-004");
        verify(reconcileSupport, never()).closeAlipayTrade("order-004");
    }

}
