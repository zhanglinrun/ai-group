package com.aigroup.groupbuy.domain.trade.service.settlement.filter;

import cn.bugstack.wrench.design.framework.link.model2.LinkArmory;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyTeamEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.MarketPayOrderEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleFilterBackEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.domain.trade.service.settlement.factory.TradeSettlementRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.GroupBuyOrderEnumVO;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

/**
 * Offline settlement status checks: repository is mocked, no MySQL/Redis/MQ required.
 */
public class SettableRuleFilterTeamStatusTest {

    private static final String TEAM_ID = "T001";

    private ITradeRepository repository;
    private SettableRuleFilter settableRuleFilter;
    private BusinessLinkedList<TradeSettlementRuleCommandEntity, TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity> chain;

    @Before
    public void setUp() {
        repository = Mockito.mock(ITradeRepository.class);
        settableRuleFilter = new SettableRuleFilter();
        ReflectionTestUtils.setField(settableRuleFilter, "repository", repository);
        chain = new LinkArmory<TradeSettlementRuleCommandEntity, TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity>(
                "settlement test", settableRuleFilter, new EndRuleFilter()).getLogicLink();
    }

    @Test
    public void shouldRejectCreateWhenTeamFailed() throws Exception {
        assertRejectedAsFinalized(GroupBuyOrderEnumVO.FAIL, TradeOrderStatusEnumVO.CREATE);
    }

    @Test
    public void shouldRejectCreateWhenTeamCompleted() throws Exception {
        assertRejectedAsFinalized(GroupBuyOrderEnumVO.COMPLETE, TradeOrderStatusEnumVO.CREATE);
    }

    @Test
    public void shouldRejectCreateWhenTeamCompletedWithRefund() throws Exception {
        assertRejectedAsFinalized(GroupBuyOrderEnumVO.COMPLETE_FAIL, TradeOrderStatusEnumVO.CREATE);
    }

    @Test
    public void shouldRejectCompletedMemberWhenTeamFailed() throws Exception {
        assertRejectedAsFinalized(GroupBuyOrderEnumVO.FAIL, TradeOrderStatusEnumVO.COMPLETE);
    }

    @Test
    public void shouldReplayCompletedMemberWhenTeamCompletedWithRefund() throws Exception {
        TradeSettlementRuleFilterBackEntity result = chain.apply(command(hoursFromNow(2)),
                contextWithTeam(GroupBuyOrderEnumVO.COMPLETE_FAIL, TradeOrderStatusEnumVO.COMPLETE));
        assertEquals(GroupBuyOrderEnumVO.COMPLETE_FAIL, result.getStatus());
        assertEquals(TEAM_ID, result.getTeamId());
    }

    @Test
    public void shouldForwardCompletedMemberOfCompletedTeamAfterExpiry() throws Exception {
        TradeSettlementRuleFilterFactory.DynamicContext dynamicContext = contextWithTeam(GroupBuyOrderEnumVO.COMPLETE, TradeOrderStatusEnumVO.COMPLETE);

        TradeSettlementRuleFilterBackEntity result = chain.apply(command(hoursFromNow(2)), dynamicContext);

        assertNotNull(result);
        assertEquals(TEAM_ID, result.getTeamId());
        assertEquals(GroupBuyOrderEnumVO.COMPLETE, result.getStatus());
        assertEquals(TEAM_ID, dynamicContext.getGroupBuyTeamEntity().getTeamId());
    }

    @Test
    public void shouldContinueChainWhenTeamInProgress() throws Exception {
        TradeSettlementRuleFilterFactory.DynamicContext dynamicContext = contextWithTeam(GroupBuyOrderEnumVO.PROGRESS, TradeOrderStatusEnumVO.CREATE);

        TradeSettlementRuleFilterBackEntity result = chain.apply(command(), dynamicContext);

        assertNotNull(result);
        assertEquals(TEAM_ID, result.getTeamId());
        assertEquals(GroupBuyOrderEnumVO.PROGRESS, result.getStatus());
    }

    @Test
    public void shouldReplayCompletedMemberAfterProgressTeamExpiry() throws Exception {
        TradeSettlementRuleFilterBackEntity result = chain.apply(command(hoursFromNow(2)),
                contextWithTeam(GroupBuyOrderEnumVO.PROGRESS, TradeOrderStatusEnumVO.COMPLETE));
        assertEquals(GroupBuyOrderEnumVO.PROGRESS, result.getStatus());
        assertEquals(TEAM_ID, result.getTeamId());
    }
    @Test
    public void shouldRejectLateTradeWhenTeamInProgress() throws Exception {
        TradeSettlementRuleFilterFactory.DynamicContext dynamicContext = contextWithTeam(GroupBuyOrderEnumVO.PROGRESS, TradeOrderStatusEnumVO.CREATE);

        try {
            chain.apply(command(hoursFromNow(2)), dynamicContext);
            fail("expected E0106 for a trade after the team's valid end time");
        } catch (AppException e) {
            assertEquals(ResponseCode.E0106.getCode(), e.getCode());
        }
        assertNull(dynamicContext.getGroupBuyTeamEntity());
    }

    private void assertRejectedAsFinalized(GroupBuyOrderEnumVO status, TradeOrderStatusEnumVO orderStatus) throws Exception {
        TradeSettlementRuleFilterFactory.DynamicContext dynamicContext = contextWithTeam(status, orderStatus);
        try {
            chain.apply(command(), dynamicContext);
            fail("expected AppException for finalized team, status=" + status + ", orderStatus=" + orderStatus);
        } catch (AppException e) {
            assertEquals(ResponseCode.E0107.getCode(), e.getCode());
            assertNotEquals(ResponseCode.UPDATE_ZERO.getCode(), e.getCode());
        }
        assertNull(dynamicContext.getGroupBuyTeamEntity());
    }

    private TradeSettlementRuleFilterFactory.DynamicContext contextWithTeam(GroupBuyOrderEnumVO status, TradeOrderStatusEnumVO orderStatus) {
        when(repository.queryGroupBuyTeamByTeamId(TEAM_ID)).thenReturn(GroupBuyTeamEntity.builder()
                .teamId(TEAM_ID)
                .activityId(100L)
                .targetCount(2)
                .completeCount(1)
                .lockCount(2)
                .status(status)
                .validStartTime(new Date(System.currentTimeMillis() - 60_000L))
                .validEndTime(hoursFromNow(1))
                .build());

        TradeSettlementRuleFilterFactory.DynamicContext dynamicContext = new TradeSettlementRuleFilterFactory.DynamicContext();
        dynamicContext.setMarketPayOrderEntity(MarketPayOrderEntity.builder()
                .teamId(TEAM_ID)
                .orderId("O001")
                .tradeOrderStatusEnumVO(orderStatus)
                .build());
        return dynamicContext;
    }

    private TradeSettlementRuleCommandEntity command() {
        return command(new Date());
    }

    private TradeSettlementRuleCommandEntity command(Date outTradeTime) {
        return TradeSettlementRuleCommandEntity.builder()
                .source("s01")
                .channel("c01")
                .userId("u001")
                .outTradeNo("OT001")
                .outTradeTime(outTradeTime)
                .build();
    }

    private Date hoursFromNow(int hours) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR, hours);
        return calendar.getTime();
    }

}
