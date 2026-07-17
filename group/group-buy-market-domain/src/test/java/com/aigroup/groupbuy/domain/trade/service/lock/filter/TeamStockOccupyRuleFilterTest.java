package com.aigroup.groupbuy.domain.trade.service.lock.filter;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyActivityEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyTeamEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.GroupBuyOrderEnumVO;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
    public void existingTeamUsesSnapshotCapacityInsteadOfMutableActivityTarget() throws Exception {
        when(repository.queryGroupBuyTeamByTeamId("team-1")).thenReturn(team(
                100201L, GroupBuyOrderEnumVO.PROGRESS, 7, 20, minutesFromNow(30)));
        when(repository.occupyTeamStock(anyString(), anyString(), eq(20), anyInt())).thenReturn(true);

        filter.apply(command(100201L), context(100201L, 10));

        verify(repository).occupyTeamStock(anyString(), anyString(), eq(20), intThat(minutes -> minutes > 0 && minutes <= 30));
    }

    @Test
    public void rejectsTeamFromAnotherActivityBeforeOccupyingStock() {
        when(repository.queryGroupBuyTeamByTeamId("team-1")).thenReturn(team(
                100202L, GroupBuyOrderEnumVO.PROGRESS, 1, 10, minutesFromNow(30)));

        AppException error = assertThrows(AppException.class,
                () -> filter.apply(command(100201L), context(100201L, 10)));

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), error.getCode());
        verify(repository, never()).occupyTeamStock(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    public void rejectsFinalizedOrExpiredTeamBeforeOccupyingStock() {
        when(repository.queryGroupBuyTeamByTeamId("team-1")).thenReturn(team(
                100201L, GroupBuyOrderEnumVO.COMPLETE, 3, 10, minutesFromNow(30)));
        AppException finalized = assertThrows(AppException.class,
                () -> filter.apply(command(100201L), context(100201L, 10)));
        assertEquals(ResponseCode.E0107.getCode(), finalized.getCode());

        when(repository.queryGroupBuyTeamByTeamId("team-1")).thenReturn(team(
                100201L, GroupBuyOrderEnumVO.PROGRESS, 2, 10, minutesFromNow(-1)));
        AppException expired = assertThrows(AppException.class,
                () -> filter.apply(command(100201L), context(100201L, 10)));
        assertEquals(ResponseCode.E0106.getCode(), expired.getCode());

        verify(repository, never()).occupyTeamStock(anyString(), anyString(), anyInt(), anyInt());
    }

    private TradeLockRuleCommandEntity command(Long activityId) {
        return TradeLockRuleCommandEntity.builder()
                .activityId(activityId)
                .userId("user-1")
                .teamId("team-1")
                .build();
    }

    private TradeLockRuleFilterFactory.DynamicContext context(Long activityId, int mutableTarget) {
        TradeLockRuleFilterFactory.DynamicContext context = new TradeLockRuleFilterFactory.DynamicContext();
        context.setGroupBuyActivity(GroupBuyActivityEntity.builder()
                .activityId(activityId)
                .target(mutableTarget)
                .validTime(1440)
                .build());
        context.setUserTakeOrderCount(0);
        return context;
    }

    private GroupBuyTeamEntity team(Long activityId, GroupBuyOrderEnumVO status,
                                    int lockCount, int targetCount, Date validEndTime) {
        return GroupBuyTeamEntity.builder()
                .teamId("team-1")
                .activityId(activityId)
                .status(status)
                .lockCount(lockCount)
                .targetCount(targetCount)
                .validEndTime(validEndTime)
                .build();
    }

    private Date minutesFromNow(int minutes) {
        return new Date(System.currentTimeMillis() + minutes * 60_000L);
    }
}
