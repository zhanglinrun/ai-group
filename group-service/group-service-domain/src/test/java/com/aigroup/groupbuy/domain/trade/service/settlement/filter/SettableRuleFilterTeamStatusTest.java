package com.aigroup.groupbuy.domain.trade.service.settlement.filter;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyTeamEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.MarketPayOrderEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleFilterBackEntity;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

/**
 * B2 regression: when the team already reached a terminal state
 * (COMPLETE / FAIL / COMPLETE_FAIL), the settlement rule chain must reject the
 * pay-success callback with the recognizable E0107 code BEFORE entering the
 * settlement transaction, instead of failing later with the ambiguous
 * UPDATE_ZERO rollback that leaves a paid order stuck forever.
 * <p>
 * Offline test: repository is mocked, no MySQL/Redis/MQ required.
 */
public class SettableRuleFilterTeamStatusTest {

    private static final String TEAM_ID = "T001";

    private ITradeRepository repository;
    private SettableRuleFilter settableRuleFilter;

    @Before
    public void setUp() {
        repository = Mockito.mock(ITradeRepository.class);
        settableRuleFilter = new SettableRuleFilter();
        ReflectionTestUtils.setField(settableRuleFilter, "repository", repository);
    }

    @Test
    public void shouldRejectWithE0107WhenTeamFailed() throws Exception {
        assertRejectedAsFinalized(GroupBuyOrderEnumVO.FAIL);
    }

    @Test
    public void shouldRejectWithE0107WhenTeamCompleted() throws Exception {
        assertRejectedAsFinalized(GroupBuyOrderEnumVO.COMPLETE);
    }

    @Test
    public void shouldRejectWithE0107WhenTeamCompletedWithRefund() throws Exception {
        assertRejectedAsFinalized(GroupBuyOrderEnumVO.COMPLETE_FAIL);
    }

    @Test
    public void shouldContinueChainWhenTeamInProgress() throws Exception {
        TradeSettlementRuleFilterFactory.DynamicContext dynamicContext = contextWithTeam(GroupBuyOrderEnumVO.PROGRESS);

        TradeSettlementRuleFilterBackEntity result = settableRuleFilter.apply(command(), dynamicContext);

        // null means the handler yielded to the next node in the chain
        assertNull(result);
        assertEquals(TEAM_ID, dynamicContext.getGroupBuyTeamEntity().getTeamId());
    }

    private void assertRejectedAsFinalized(GroupBuyOrderEnumVO status) throws Exception {
        TradeSettlementRuleFilterFactory.DynamicContext dynamicContext = contextWithTeam(status);
        try {
            settableRuleFilter.apply(command(), dynamicContext);
            fail("expected AppException for finalized team, status=" + status);
        } catch (AppException e) {
            assertEquals(ResponseCode.E0107.getCode(), e.getCode());
            assertNotEquals(ResponseCode.UPDATE_ZERO.getCode(), e.getCode());
        }
    }

    private TradeSettlementRuleFilterFactory.DynamicContext contextWithTeam(GroupBuyOrderEnumVO status) {
        when(repository.queryGroupBuyTeamByTeamId(TEAM_ID)).thenReturn(GroupBuyTeamEntity.builder()
                .teamId(TEAM_ID)
                .activityId(100L)
                .targetCount(2)
                .completeCount(1)
                .lockCount(2)
                .status(status)
                .validStartTime(new Date(System.currentTimeMillis() - 60_000L))
                .validEndTime(inOneHour())
                .build());

        TradeSettlementRuleFilterFactory.DynamicContext dynamicContext = new TradeSettlementRuleFilterFactory.DynamicContext();
        dynamicContext.setMarketPayOrderEntity(MarketPayOrderEntity.builder()
                .teamId(TEAM_ID)
                .orderId("O001")
                .build());
        return dynamicContext;
    }

    private TradeSettlementRuleCommandEntity command() {
        return TradeSettlementRuleCommandEntity.builder()
                .source("s01")
                .channel("c01")
                .userId("u001")
                .outTradeNo("OT001")
                .outTradeTime(new Date())
                .build();
    }

    private Date inOneHour() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR, 1);
        return calendar.getTime();
    }

}
