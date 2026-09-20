package com.aigroup.groupbuy.domain.trade.service;

import cn.bugstack.wrench.design.framework.link.model2.LinkArmory;
import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.*;
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.domain.trade.service.refund.business.IRefundOrderStrategy;
import com.aigroup.groupbuy.domain.trade.service.refund.factory.TradeRefundRuleFilterFactory;
import com.aigroup.groupbuy.domain.trade.service.refund.filter.DataNodeFilter;
import com.aigroup.groupbuy.domain.trade.service.refund.filter.RefundOrderNodeFilter;
import com.aigroup.groupbuy.domain.trade.service.refund.filter.UniqueRefundNodeFilter;
import com.aigroup.groupbuy.domain.trade.service.settlement.factory.TradeSettlementRuleFilterFactory;
import com.aigroup.groupbuy.domain.trade.service.settlement.filter.EndRuleFilter;
import com.aigroup.groupbuy.domain.trade.service.settlement.filter.OutTradeNoRuleFilter;
import com.aigroup.groupbuy.domain.trade.service.settlement.filter.SettableRuleFilter;
import com.aigroup.groupbuy.types.enums.GroupBuyOrderEnumVO;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TradeBusinessKeyFilterTest {

    @Test
    public void settlementRejectsWrongChannelAndAcceptsMatchingOrderAndReplay() throws Exception {
        ITradeRepository repository = mock(ITradeRepository.class);
        OutTradeNoRuleFilter lookup = new OutTradeNoRuleFilter();
        SettableRuleFilter settable = new SettableRuleFilter();
        ReflectionTestUtils.setField(lookup, "repository", repository);
        ReflectionTestUtils.setField(settable, "repository", repository);
        var chain = new LinkArmory<TradeSettlementRuleCommandEntity,
                TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity>(
                "scoped settlement", lookup, settable, new EndRuleFilter()).getLogicLink();
        when(repository.queryMarketPayOrderEntityByBusinessKey("u", "s", "c1", "no"))
                .thenReturn(order("team1", TradeOrderStatusEnumVO.CREATE));
        when(repository.queryGroupBuyTeamByTeamId("team1")).thenReturn(team("team1"));
        TradeSettlementRuleCommandEntity command = TradeSettlementRuleCommandEntity.builder()
                .userId("u").source("s").channel("c2").outTradeNo("no").outTradeTime(new Date()).build();
        try {
            chain.apply(command, new TradeSettlementRuleFilterFactory.DynamicContext());
            fail("wrong channel must not settle the c1 order");
        } catch (AppException e) {
            assertEquals(ResponseCode.E0104.getCode(), e.getCode());
        }
        verify(repository, never()).queryGroupBuyTeamByTeamId(any());

        when(repository.queryMarketPayOrderEntityByBusinessKey("u", "s", "c2", "no"))
                .thenReturn(order("team2", TradeOrderStatusEnumVO.CREATE));
        when(repository.queryGroupBuyTeamByTeamId("team2")).thenReturn(team("team2"));
        assertEquals("team2", chain.apply(command, new TradeSettlementRuleFilterFactory.DynamicContext()).getTeamId());
        when(repository.queryMarketPayOrderEntityByBusinessKey("u", "s", "c2", "no"))
                .thenReturn(order("team2", TradeOrderStatusEnumVO.COMPLETE));
        assertEquals("team2", chain.apply(command, new TradeSettlementRuleFilterFactory.DynamicContext()).getTeamId());
    }

    @Test
    public void refundRejectsWrongChannelAndPreservesKeyForMatchingOrderAndReplay() throws Exception {
        ITradeRepository repository = mock(ITradeRepository.class);
        DataNodeFilter lookup = new DataNodeFilter();
        ReflectionTestUtils.setField(lookup, "repository", repository);
        RefundOrderNodeFilter refund = new RefundOrderNodeFilter();
        IRefundOrderStrategy strategy = mock(IRefundOrderStrategy.class);
        ReflectionTestUtils.setField(refund, "refundOrderStrategyMap", Map.of("unpaid2RefundStrategy", strategy));
        var chain = new LinkArmory<TradeRefundCommandEntity,
                TradeRefundRuleFilterFactory.DynamicContext, TradeRefundBehaviorEntity>(
                "scoped refund", lookup, new UniqueRefundNodeFilter(), refund).getLogicLink();
        when(repository.queryMarketPayOrderEntityByBusinessKey("u", "s", "c1", "no"))
                .thenReturn(order("team1", TradeOrderStatusEnumVO.CREATE));
        TradeRefundCommandEntity command = TradeRefundCommandEntity.builder()
                .userId("u").source("s").channel("c2").outTradeNo("no").build();
        try {
            chain.apply(command, new TradeRefundRuleFilterFactory.DynamicContext());
            fail("wrong channel must not refund the c1 order");
        } catch (AppException e) {
            assertEquals(ResponseCode.E0104.getCode(), e.getCode());
        }
        verifyNoInteractions(strategy);

        when(repository.queryMarketPayOrderEntityByBusinessKey("u", "s", "c2", "no"))
                .thenReturn(order("team2", TradeOrderStatusEnumVO.CREATE));
        when(repository.queryGroupBuyTeamByTeamId("team2")).thenReturn(team("team2"));
        assertEquals("team2", chain.apply(command, new TradeRefundRuleFilterFactory.DynamicContext()).getTeamId());
        ArgumentCaptor<TradeRefundOrderEntity> captured = ArgumentCaptor.forClass(TradeRefundOrderEntity.class);
        verify(strategy).refundOrder(captured.capture());
        assertEquals("s", captured.getValue().getSource());
        assertEquals("c2", captured.getValue().getChannel());
        assertEquals("no", captured.getValue().getOutTradeNo());
        assertEquals("order-team2", captured.getValue().getOrderId());

        when(repository.queryMarketPayOrderEntityByBusinessKey("u", "s", "c2", "no"))
                .thenReturn(order("team2", TradeOrderStatusEnumVO.CLOSE));
        assertEquals(TradeRefundBehaviorEntity.TradeRefundBehaviorEnum.REPEAT,
                chain.apply(command, new TradeRefundRuleFilterFactory.DynamicContext()).getTradeRefundBehaviorEnum());
        verifyNoMoreInteractions(strategy);
    }

    private MarketPayOrderEntity order(String teamId, TradeOrderStatusEnumVO status) {
        return MarketPayOrderEntity.builder().teamId(teamId).orderId("order-" + teamId)
                .tradeOrderStatusEnumVO(status).build();
    }

    private GroupBuyTeamEntity team(String teamId) {
        return GroupBuyTeamEntity.builder().teamId(teamId).activityId(1L)
                .status(GroupBuyOrderEnumVO.PROGRESS).validEndTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
    }
}
