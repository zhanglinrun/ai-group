package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import com.aigroup.groupbuy.domain.trade.model.aggregate.GroupBuyTeamSettlementAggregate;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyTeamEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.PayActivityEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.PayDiscountEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySuccessEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.UserEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.NotifyConfigVO;
import com.aigroup.groupbuy.domain.trade.model.valobj.NotifyTypeEnumVO;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyActivityTierDao;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyOrderDao;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyOrderListDao;
import com.aigroup.groupbuy.infrastructure.dao.INotifyTaskDao;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyActivityTier;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyOrder;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyOrderList;
import com.aigroup.groupbuy.infrastructure.dao.po.NotifyTask;
import com.aigroup.groupbuy.types.common.JsonUtils;
import com.aigroup.groupbuy.types.common.JsonUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TradeRepositoryTierSnapshotTest {

    private TradeRepository repository;
    private IGroupBuyActivityTierDao tierDao;
    private IGroupBuyOrderDao orderDao;
    private IGroupBuyOrderListDao orderListDao;
    private INotifyTaskDao notifyTaskDao;

    @Before
    public void setUp() {
        repository = new TradeRepository();
        tierDao = mock(IGroupBuyActivityTierDao.class);
        orderDao = mock(IGroupBuyOrderDao.class);
        orderListDao = mock(IGroupBuyOrderListDao.class);
        notifyTaskDao = mock(INotifyTaskDao.class);
        ReflectionTestUtils.setField(repository, "groupBuyActivityTierDao", tierDao);
        ReflectionTestUtils.setField(repository, "groupBuyOrderDao", orderDao);
        ReflectionTestUtils.setField(repository, "groupBuyOrderListDao", orderListDao);
        ReflectionTestUtils.setField(repository, "notifyTaskDao", notifyTaskDao);
        ReflectionTestUtils.setField(repository, "topic_team_success", "topic.team_success");
    }

    @Test
    public void newTeamSnapshotsTiersUsesMaxCapacityAndKeepsCashPrice() {
        when(tierDao.queryTiersByActivityId(100201L)).thenReturn(lightTiers());

        repository.lockMarketPayOrder(GroupBuyOrderAggregate.builder()
                .userEntity(UserEntity.builder().userId("u1").build())
                .payActivityEntity(PayActivityEntity.builder()
                        .activityId(100201L).validTime(1440).targetCount(2).build())
                .payDiscountEntity(PayDiscountEntity.builder()
                        .source("s01").channel("c01").goodsId("9890002")
                        .originalPrice(new BigDecimal("12.00"))
                        .deductionPrice(new BigDecimal("3.00"))
                        .payPrice(new BigDecimal("9.00"))
                        .outTradeNo("pay-1")
                        .notifyConfigVO(NotifyConfigVO.builder().notifyType(NotifyTypeEnumVO.MQ).build())
                        .build())
                .userTakeOrderCount(0)
                .build());

        ArgumentCaptor<GroupBuyOrder> team = ArgumentCaptor.forClass(GroupBuyOrder.class);
        verify(orderDao).insert(team.capture());
        assertEquals(Integer.valueOf(10), team.getValue().getTargetCount());
        assertEquals(3, JsonUtils.parseArray(team.getValue().getTierSnapshot(), GroupBuyActivityTier.class).size());
        assertEquals(0, new BigDecimal("12.00").compareTo(team.getValue().getPayPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(team.getValue().getDeductionPrice()));
    }

    @Test
    public void maxTierSettlesImmediatelyUsingTeamSnapshot() {
        // Simulate an operator changing the live activity after this team was created.
        when(tierDao.queryTiersByActivityId(100201L))
                .thenReturn(Collections.singletonList(tier(1, 3, 999)));
        when(orderListDao.updateOrderStatus2COMPLETE(any())).thenReturn(1);
        when(orderDao.updateAddCompleteCount("TEAM1")).thenReturn(1);
        when(orderDao.queryGroupBuyProgress("TEAM1")).thenReturn(team("TEAM1", 10));
        when(orderDao.updateOrderStatus2COMPLETE("TEAM1")).thenReturn(1);
        when(orderListDao.queryGroupBuyCompleteOrderOutTradeNoListByTeamId("TEAM1"))
                .thenReturn(Collections.singletonList("pay-1"));

        repository.settlementMarketPayOrder(settlement("TEAM1"));

        ArgumentCaptor<NotifyTask> task = ArgumentCaptor.forClass(NotifyTask.class);
        verify(notifyTaskDao).insert(task.capture());
        Map<String, Object> payload = JsonUtils.parseObject(task.getValue().getParameterJson());
        assertEquals(18, ((Number) payload.get("bonusQuota")).intValue());
        verify(tierDao, never()).queryTiersByActivityId(any());
    }

    @Test
    public void timeoutUsesHighestReachedTierFromSnapshot() {
        GroupBuyOrder team = team("TEAM2", 5);
        team.setNotifyType(NotifyTypeEnumVO.MQ.getCode());
        when(orderDao.queryExpiredProgressTeams()).thenReturn(Collections.singletonList(team));
        when(orderDao.queryGroupBuyTeamByTeamIdForUpdate("TEAM2")).thenReturn(team);
        when(orderListDao.queryGroupBuyCompleteOrderOutTradeNoListByTeamId("TEAM2"))
                .thenReturn(Arrays.asList("pay-1", "pay-2", "pay-3", "pay-4", "pay-5"));
        when(orderDao.updateOrderStatus2COMPLETE("TEAM2")).thenReturn(1);

        assertEquals(1, repository.settleExpiredFormedTeams());

        ArgumentCaptor<NotifyTask> task = ArgumentCaptor.forClass(NotifyTask.class);
        verify(notifyTaskDao).insert(task.capture());
        assertEquals(12, ((Number) JsonUtils.parseObject(task.getValue().getParameterJson()).get("bonusQuota")).intValue());
        verify(orderDao).updateOrderStatus2COMPLETE("TEAM2");
    }

    @Test
    public void timeoutBelowMinimumIsLeftForExistingRefundFlow() {
        GroupBuyOrder team = team("TEAM3", 2);
        when(orderDao.queryExpiredProgressTeams()).thenReturn(Collections.singletonList(team));
        when(orderDao.queryGroupBuyTeamByTeamIdForUpdate("TEAM3")).thenReturn(team);

        assertEquals(0, repository.settleExpiredFormedTeams());

        verify(notifyTaskDao, never()).insert(any());
        verify(orderDao, never()).updateOrderStatus2COMPLETE("TEAM3");
    }

    @Test
    public void paidRefundScanSkipsTierTeamThatAlreadyReachedMinimum() {
        GroupBuyOrderList eligible = detail("TEAM-ELIGIBLE", "pay-eligible");
        GroupBuyOrderList below = detail("TEAM-BELOW", "pay-below");
        when(orderListDao.queryTimeoutPaidUnformedOrderList()).thenReturn(Arrays.asList(eligible, below));

        GroupBuyOrder eligibleTeam = team("TEAM-ELIGIBLE", 3);
        GroupBuyOrder belowTeam = team("TEAM-BELOW", 2);
        when(orderDao.queryGroupBuyTeamByTeamIds(Set.of("TEAM-ELIGIBLE", "TEAM-BELOW")))
                .thenReturn(Arrays.asList(eligibleTeam, belowTeam));

        assertEquals(Collections.singletonList("pay-below"), repository.queryTimeoutPaidUnformedOrderList()
                .stream().map(detail -> detail.getOutTradeNo()).toList());
    }

    private GroupBuyTeamSettlementAggregate settlement(String teamId) {
        return GroupBuyTeamSettlementAggregate.builder()
                .userEntity(UserEntity.builder().userId("u1").build())
                .groupBuyTeamEntity(GroupBuyTeamEntity.builder()
                        .teamId(teamId).activityId(100201L).targetCount(10).completeCount(9)
                        .notifyConfigVO(NotifyConfigVO.builder()
                                .notifyType(NotifyTypeEnumVO.MQ).notifyMQ("topic.team.success").build())
                        .build())
                .tradePaySuccessEntity(TradePaySuccessEntity.builder()
                        .userId("u1").outTradeNo("pay-10").outTradeTime(new Date()).build())
                .build();
    }

    private GroupBuyOrder team(String teamId, int completeCount) {
        return GroupBuyOrder.builder()
                .teamId(teamId)
                .activityId(100201L)
                .targetCount(10)
                .tierSnapshot(JsonUtils.toJson(lightTiers()))
                .completeCount(completeCount)
                .lockCount(completeCount)
                .status(0)
                .validEndTime(new Date(System.currentTimeMillis() - 60_000L))
                .build();
    }

    private GroupBuyOrderList detail(String teamId, String outTradeNo) {
        return GroupBuyOrderList.builder()
                .userId("u1")
                .teamId(teamId)
                .orderId("group-" + outTradeNo)
                .activityId(100201L)
                .source("s01")
                .channel("c01")
                .outTradeNo(outTradeNo)
                .build();
    }

    private List<GroupBuyActivityTier> lightTiers() {
        return Arrays.asList(
                tier(1, 3, 6),
                tier(2, 5, 12),
                tier(3, 10, 18));
    }

    private GroupBuyActivityTier tier(int tierNo, int target, int bonus) {
        return GroupBuyActivityTier.builder()
                .activityId(100201L)
                .tierNo(tierNo)
                .tierName(target + "人团")
                .targetCount(target)
                .bonusQuota(bonus)
                .status(1)
                .build();
    }
}
