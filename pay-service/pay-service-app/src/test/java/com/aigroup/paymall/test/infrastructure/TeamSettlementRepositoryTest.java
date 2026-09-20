package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.order.model.entity.TeamSettlementMember;
import com.aigroup.paymall.infrastructure.adapter.repository.OrderRepository;
import com.aigroup.paymall.infrastructure.dao.IOrderDao;
import com.aigroup.paymall.infrastructure.dao.po.PayOrder;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class TeamSettlementRepositoryTest {
    private static final TeamSettlementMember MEMBER = new TeamSettlementMember("u1", "s01", "c01", "paid");

    @Test
    public void onlyExactTransitionCreatesBenefitAndDuplicateIsIdempotent() {
        IOrderDao dao = mock(IOrderDao.class);
        OrderRepository repository = repository(dao);
        AtomicInteger attempts = new AtomicInteger();
        when(dao.changeOrderMarketSettlement(any(PayOrder.class))).thenAnswer(invocation -> {
            PayOrder order = invocation.getArgument(0);
            assertEquals("t1", order.getGroupTeamId());
            assertEquals(Long.valueOf(101), order.getGroupActivityId());
            assertEquals("s01", order.getGroupSource());
            assertEquals("c01", order.getGroupChannel());
            return attempts.getAndIncrement() == 0 ? 1 : 0;
        });
        when(dao.queryOrderByOrderId("paid")).thenReturn(existing("paid", "t1", "MARKET"));

        assertEquals(List.of("paid"), repository.changeOrderMarketSettlement("t1", 101L, List.of(MEMBER)));
        assertEquals(List.of(), repository.changeOrderMarketSettlement("t1", 101L, List.of(MEMBER)));
        verify(dao).queryOrderByOrderId("paid");
    }

    @Test
    public void refundedMatchingOrderCanIgnoreLateFormationReplay() {
        IOrderDao dao = mock(IOrderDao.class);
        PayOrder refunded = existing("paid", "t1", "CLOSE");
        refunded.setPayTime(new Date());
        when(dao.queryOrderByOrderId("paid")).thenReturn(refunded);
        assertEquals(List.of(), repository(dao).changeOrderMarketSettlement("t1", 101L, List.of(MEMBER)));
    }

    @Test
    public void unpaidCloseIsNotAcknowledgedAsRefundReplay() {
        IOrderDao dao = mock(IOrderDao.class);
        when(dao.queryOrderByOrderId("paid")).thenReturn(existing("paid", "t1", "CLOSE"));
        assertThrows(IllegalStateException.class,
                () -> repository(dao).changeOrderMarketSettlement("t1", 101L, List.of(MEMBER)));
    }

    @Test
    public void mixedOwnerTeamSettlesOnlyLocalOrder() {
        IOrderDao dao = mock(IOrderDao.class);
        when(dao.changeOrderMarketSettlement(any(PayOrder.class))).thenAnswer(invocation ->
                "paid".equals(((PayOrder) invocation.getArgument(0)).getOrderId()) ? 1 : 0);
        assertEquals(List.of("paid"), repository(dao).changeOrderMarketSettlement("t1", 101L,
                List.of(MEMBER, new TeamSettlementMember("external-user", "other", "other", "external"))));
        verify(dao).queryOrderByOrderId("external");
    }

    @Test
    public void wrongTeamOrLegacyNullDoesNotAcknowledge() {
        IOrderDao dao = mock(IOrderDao.class);
        OrderRepository repository = repository(dao);
        when(dao.queryOrderByOrderId("paid")).thenReturn(existing("paid", "other-team", "PAY_SUCCESS"));
        assertThrows(IllegalStateException.class,
                () -> repository.changeOrderMarketSettlement("t1", 101L, List.of(MEMBER)));
        PayOrder legacy = existing("paid", "t1", "PAY_SUCCESS");
        legacy.setGroupSource(null);
        when(dao.queryOrderByOrderId("paid")).thenReturn(legacy);
        assertThrows(IllegalStateException.class,
                () -> repository.changeOrderMarketSettlement("t1", 101L, List.of(MEMBER)));
    }

    @Test
    public void unpaidMatchingOrderMustRetry() {
        IOrderDao dao = mock(IOrderDao.class);
        when(dao.queryOrderByOrderId("paid")).thenReturn(existing("paid", "t1", "PAY_WAIT"));
        assertThrows(IllegalStateException.class,
                () -> repository(dao).changeOrderMarketSettlement("t1", 101L, List.of(MEMBER)));
    }

    @Test
    public void emptyMemberIdentityFailsClosed() {
        IOrderDao dao = mock(IOrderDao.class);
        OrderRepository repository = repository(dao);
        assertThrows(IllegalArgumentException.class, () -> repository.changeOrderMarketSettlement(
                "t1", 101L, List.of(new TeamSettlementMember("", "s01", "c01", "paid"))));
        assertThrows(IllegalArgumentException.class, () -> repository.changeOrderMarketSettlement(
                "t1", null, List.of(MEMBER)));
        verifyNoInteractions(dao);
    }

    private OrderRepository repository(IOrderDao dao) {
        OrderRepository repository = new OrderRepository();
        ReflectionTestUtils.setField(repository, "orderDao", dao);
        return repository;
    }

    private PayOrder existing(String orderId, String teamId, String status) {
        PayOrder row = new PayOrder();
        row.setOrderId(orderId);
        row.setUserId("u1");
        row.setGroupTeamId(teamId);
        row.setGroupActivityId(101L);
        row.setGroupSource("s01");
        row.setGroupChannel("c01");
        row.setMarketType(1);
        row.setStatus(status);
        return row;
    }
}
