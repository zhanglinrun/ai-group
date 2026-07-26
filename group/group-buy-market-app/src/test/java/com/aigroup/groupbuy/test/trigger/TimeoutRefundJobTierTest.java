package com.aigroup.groupbuy.test.trigger;

import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundCommandEntity;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.domain.trade.service.ITradeSettlementOrderService;
import com.aigroup.groupbuy.trigger.job.TimeoutRefundJob;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TimeoutRefundJobTierTest {

    @Test
    public void belowMinimumPaidTeamUsesExistingAutomaticRefundFlow() throws Exception {
        ITradeRefundOrderService refundService = mock(ITradeRefundOrderService.class);
        ITradeSettlementOrderService settlementService = mock(ITradeSettlementOrderService.class);
        when(settlementService.settleExpiredFormedTeams()).thenReturn(0);
        when(refundService.queryTimeoutUnpaidOrderList()).thenReturn(Collections.emptyList());
        when(refundService.queryTimeoutPaidUnformedOrderList()).thenReturn(Collections.singletonList(
                UserGroupBuyOrderDetailEntity.builder()
                        .userId("u1").teamId("TEAM3").activityId(100201L)
                        .completeCount(2).targetCount(10).outTradeNo("pay-1")
                        .source("s01").channel("c01").build()));

        TimeoutRefundJob job = new TimeoutRefundJob();
        ReflectionTestUtils.setField(job, "tradeRefundOrderService", refundService);
        ReflectionTestUtils.setField(job, "tradeSettlementOrderService", settlementService);

        job.exec();

        ArgumentCaptor<TradeRefundCommandEntity> command = ArgumentCaptor.forClass(TradeRefundCommandEntity.class);
        verify(refundService).refundOrder(command.capture());
        assertEquals("pay-1", command.getValue().getOutTradeNo());
        assertEquals("u1", command.getValue().getUserId());
    }
}
