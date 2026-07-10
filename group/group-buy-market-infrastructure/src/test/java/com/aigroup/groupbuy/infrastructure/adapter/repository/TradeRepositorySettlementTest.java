package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.domain.trade.model.aggregate.GroupBuyTeamSettlementAggregate;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyTeamEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySuccessEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.UserEntity;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyOrderDao;
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyOrderListDao;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyOrder;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyOrderList;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * B2 fallback regression for {@link TradeRepository#settlementMarketPayOrder}:
 * when {@code updateAddCompleteCount} touches 0 rows because the team was
 * finalized concurrently (refund closed the team between the chain check and
 * this transaction), the repository must surface the recognizable E0107 code
 * instead of the ambiguous UPDATE_ZERO, so the caller can stop retrying.
 * <p>
 * Offline test: DAOs are mocked, no MySQL/Redis/MQ required.
 */
public class TradeRepositorySettlementTest {

    private static final String TEAM_ID = "T001";

    private TradeRepository tradeRepository;
    private IGroupBuyOrderDao groupBuyOrderDao;
    private IGroupBuyOrderListDao groupBuyOrderListDao;

    @Before
    public void setUp() {
        tradeRepository = new TradeRepository();
        groupBuyOrderDao = Mockito.mock(IGroupBuyOrderDao.class);
        groupBuyOrderListDao = Mockito.mock(IGroupBuyOrderListDao.class);
        ReflectionTestUtils.setField(tradeRepository, "groupBuyOrderDao", groupBuyOrderDao);
        ReflectionTestUtils.setField(tradeRepository, "groupBuyOrderListDao", groupBuyOrderListDao);

        // order detail row updates fine; the team-level update is what we vary
        when(groupBuyOrderListDao.updateOrderStatus2COMPLETE(any())).thenReturn(1);
    }

    @Test
    public void shouldThrowE0107WhenCompleteCountUpdateHitsFinalizedTeam() {
        when(groupBuyOrderDao.updateAddCompleteCount(TEAM_ID)).thenReturn(0);
        when(groupBuyOrderDao.queryGroupBuyTeamByTeamId(TEAM_ID)).thenReturn(teamWithStatus(2)); // FAIL

        try {
            tradeRepository.settlementMarketPayOrder(aggregate());
            fail("expected AppException E0107 for finalized team");
        } catch (AppException e) {
            assertEquals(ResponseCode.E0107.getCode(), e.getCode());
        }
    }

    @Test
    public void shouldKeepUpdateZeroWhenTeamStillInProgress() {
        when(groupBuyOrderDao.updateAddCompleteCount(TEAM_ID)).thenReturn(0);
        when(groupBuyOrderDao.queryGroupBuyTeamByTeamId(TEAM_ID)).thenReturn(teamWithStatus(0)); // PROGRESS

        try {
            tradeRepository.settlementMarketPayOrder(aggregate());
            fail("expected AppException UPDATE_ZERO for genuine update anomaly");
        } catch (AppException e) {
            assertEquals(ResponseCode.UPDATE_ZERO.getCode(), e.getCode());
        }
    }

    @Test
    public void shouldSettleWithoutNotifyTaskWhenTargetNotReached() throws Exception {
        when(groupBuyOrderDao.updateAddCompleteCount(TEAM_ID)).thenReturn(1);
        GroupBuyOrder progress = teamWithStatus(0);
        progress.setCompleteCount(1);
        progress.setTargetCount(2);
        when(groupBuyOrderDao.queryGroupBuyProgress(TEAM_ID)).thenReturn(progress);

        assertNull(tradeRepository.settlementMarketPayOrder(aggregate()));
    }

    /**
     * Idempotency: a duplicated / redelivered settlement callback finds the member
     * detail already COMPLETE (updateOrderStatus2COMPLETE touches 0 rows). It must
     * return null (already settled) instead of throwing UPDATE_ZERO, and must NOT
     * re-increment the team complete_count.
     */
    @Test
    public void shouldReturnNullWhenMemberOrderAlreadyCompleted() throws Exception {
        when(groupBuyOrderListDao.updateOrderStatus2COMPLETE(any())).thenReturn(0);
        GroupBuyOrderList completedDetail = new GroupBuyOrderList();
        completedDetail.setStatus(TradeOrderStatusEnumVO.COMPLETE.getCode());
        when(groupBuyOrderListDao.queryGroupBuyOrderRecordByOutTradeNo(any())).thenReturn(completedDetail);

        assertNull(tradeRepository.settlementMarketPayOrder(aggregate()));
        Mockito.verify(groupBuyOrderDao, Mockito.never()).updateAddCompleteCount(any());
    }

    /**
     * When the detail update touches 0 rows but the detail is NOT already complete
     * (a genuine anomaly, e.g. wrong user / missing row), keep throwing UPDATE_ZERO.
     */
    @Test
    public void shouldThrowUpdateZeroWhenMemberOrderMissingOrNotComplete() {
        when(groupBuyOrderListDao.updateOrderStatus2COMPLETE(any())).thenReturn(0);
        when(groupBuyOrderListDao.queryGroupBuyOrderRecordByOutTradeNo(any())).thenReturn(null);

        try {
            tradeRepository.settlementMarketPayOrder(aggregate());
            fail("expected AppException UPDATE_ZERO when member detail is missing/not complete");
        } catch (AppException e) {
            assertEquals(ResponseCode.UPDATE_ZERO.getCode(), e.getCode());
        }
    }

    private GroupBuyOrder teamWithStatus(int status) {
        GroupBuyOrder groupBuyOrder = new GroupBuyOrder();
        groupBuyOrder.setTeamId(TEAM_ID);
        groupBuyOrder.setStatus(status);
        return groupBuyOrder;
    }

    private GroupBuyTeamSettlementAggregate aggregate() {
        return GroupBuyTeamSettlementAggregate.builder()
                .userEntity(UserEntity.builder().userId("u001").build())
                .groupBuyTeamEntity(GroupBuyTeamEntity.builder()
                        .teamId(TEAM_ID)
                        .activityId(100L)
                        .targetCount(2)
                        .completeCount(1)
                        .build())
                .tradePaySuccessEntity(TradePaySuccessEntity.builder()
                        .userId("u001")
                        .outTradeNo("OT001")
                        .outTradeTime(new Date())
                        .build())
                .build();
    }

}
