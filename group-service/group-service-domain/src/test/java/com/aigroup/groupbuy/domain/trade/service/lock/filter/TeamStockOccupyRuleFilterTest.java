package com.aigroup.groupbuy.domain.trade.service.lock.filter;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyActivityEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.aigroup.groupbuy.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

public class TeamStockOccupyRuleFilterTest {

    private ITradeRepository repository;
    private TeamStockOccupyRuleFilter filter;

    @Before
    public void setUp() {
        repository = mock(ITradeRepository.class);
        filter = new TeamStockOccupyRuleFilter();
        ReflectionTestUtils.setField(filter, "repository", repository);
    }

    @Test
    public void joinOccupiesRedisUsingActivityTarget() throws Exception {
        when(repository.occupyTeamStock(anyString(), anyString(), eq(10), eq(1440))).thenReturn(true);

        TradeLockRuleFilterBackEntity back = filter.apply(command(), context(10));

        assertEquals("group_buy_market_team_stock_key_100201_team-1_recovery", back.getRecoveryTeamStockKey());
        verify(repository).occupyTeamStock(
                "group_buy_market_team_stock_key_100201_team-1",
                "group_buy_market_team_stock_key_100201_team-1_recovery",
                10,
                1440);
    }

    @Test
    public void openTeamDoesNotOccupy() throws Exception {
        TradeLockRuleCommandEntity open = TradeLockRuleCommandEntity.builder()
                .activityId(100201L)
                .userId("user-1")
                .build();

        TradeLockRuleFilterBackEntity back = filter.apply(open, context(10));

        assertNull(back.getRecoveryTeamStockKey());
        verify(repository, never()).occupyTeamStock(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    public void occupyFailureThrowsE0008() {
        when(repository.occupyTeamStock(anyString(), anyString(), eq(10), eq(1440))).thenReturn(false);

        AppException error = assertThrows(AppException.class, () -> filter.apply(command(), context(10)));

        assertEquals(ResponseCode.E0008.getCode(), error.getCode());
    }

    private TradeLockRuleCommandEntity command() {
        return TradeLockRuleCommandEntity.builder()
                .activityId(100201L)
                .userId("user-1")
                .teamId("team-1")
                .build();
    }

    private TradeLockRuleFilterFactory.DynamicContext context(int target) {
        TradeLockRuleFilterFactory.DynamicContext context = new TradeLockRuleFilterFactory.DynamicContext();
        context.setGroupBuyActivity(GroupBuyActivityEntity.builder()
                .activityId(100201L)
                .target(target)
                .validTime(1440)
                .build());
        context.setUserTakeOrderCount(0);
        return context;
    }
}
