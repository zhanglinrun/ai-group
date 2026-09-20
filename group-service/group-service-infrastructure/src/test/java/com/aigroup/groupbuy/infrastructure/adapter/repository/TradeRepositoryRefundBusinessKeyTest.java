package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.domain.trade.model.aggregate.GroupBuyRefundAggregate;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundOrderEntity;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyOrderListDao;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyOrderList;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

public class TradeRepositoryRefundBusinessKeyTest {

    @Test
    public void eachRefundUpdateCarriesOrderIdAndFullBusinessKey() {
        TradeRepository repository = new TradeRepository();
        IGroupBuyOrderListDao dao = mock(IGroupBuyOrderListDao.class);
        ReflectionTestUtils.setField(repository, "groupBuyOrderListDao", dao);
        TradeRefundOrderEntity order = TradeRefundOrderEntity.builder().userId("u")
                .source("s").channel("c2").outTradeNo("no").orderId("order2").build();
        GroupBuyRefundAggregate aggregate = GroupBuyRefundAggregate.builder().tradeRefundOrderEntity(order).build();
        for (int path = 0; path < 3; path++) {
            try {
                switch (path) {
                    case 0 -> repository.unpaid2Refund(aggregate);
                    case 1 -> repository.paid2Refund(aggregate);
                    default -> repository.paidTeam2Refund(aggregate);
                }
                fail("zero rows must reject the refund");
            } catch (AppException e) {
                assertEquals(ResponseCode.UPDATE_ZERO.getCode(), e.getCode());
            }
        }
        ArgumentCaptor<GroupBuyOrderList> unpaid = ArgumentCaptor.forClass(GroupBuyOrderList.class);
        ArgumentCaptor<GroupBuyOrderList> paid = ArgumentCaptor.forClass(GroupBuyOrderList.class);
        ArgumentCaptor<GroupBuyOrderList> formed = ArgumentCaptor.forClass(GroupBuyOrderList.class);
        verify(dao).unpaid2Refund(unpaid.capture());
        verify(dao).paid2Refund(paid.capture());
        verify(dao).paidTeam2Refund(formed.capture());
        for (GroupBuyOrderList key : new GroupBuyOrderList[]{unpaid.getValue(), paid.getValue(), formed.getValue()}) {
            assertEquals("u", key.getUserId());
            assertEquals("s", key.getSource());
            assertEquals("c2", key.getChannel());
            assertEquals("no", key.getOutTradeNo());
            assertEquals("order2", key.getOrderId());
        }
    }
}
