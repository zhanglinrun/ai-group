package com.aigroup.groupbuy.test.trigger;

import com.aigroup.groupbuy.api.dto.GoodsMarketRequestDTO;
import com.aigroup.groupbuy.api.dto.GoodsMarketResponseDTO;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.TeamStatisticVO;
import com.aigroup.groupbuy.domain.activity.service.IIndexGroupBuyMarketService;
import com.aigroup.groupbuy.trigger.http.MarketIndexController;
import com.aigroup.groupbuy.types.common.JsonUtils;
import com.aigroup.common.context.RequestUserContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MarketIndexTierSnapshotTest {

    @Before
    public void bindUser() {
        RequestUserContext.bind(1L, "user-1", "USER");
    }

    @After
    public void clearUser() {
        RequestUserContext.clear();
    }

    @Test
    public void shouldExposeTeamSnapshotInsteadOfEditedLiveTiers() throws Exception {
        IIndexGroupBuyMarketService marketService = mock(IIndexGroupBuyMarketService.class);
        MarketIndexController controller = new MarketIndexController();
        ReflectionTestUtils.setField(controller, "indexGroupBuyMarketService", marketService);

        List<GroupBuyActivityDiscountVO.Tier> liveTiers = List.of(
                tier(1, 3, 999), tier(2, 5, 999), tier(3, 10, 999));
        when(marketService.indexMarketTrial(any())).thenReturn(TrialBalanceEntity.builder()
                .goodsId("9890002")
                .originalPrice(new BigDecimal("12.00"))
                // Legacy discount calculation must not leak into a tiered-quota activity response.
                .deductionPrice(new BigDecimal("10.00"))
                .payPrice(new BigDecimal("2.00"))
                .groupBuyActivityDiscountVO(GroupBuyActivityDiscountVO.builder()
                        .activityId(100201L)
                        .activityType(1)
                        .tiers(liveTiers)
                        .build())
                .build());

        List<GoodsMarketResponseDTO.Tier> snapshotTiers = List.of(
                responseTier(1, 3, 6), responseTier(2, 5, 12), responseTier(3, 10, 18));
        Date now = new Date();
        when(marketService.queryInProgressUserGroupBuyOrderDetailList(100201L, "1", 3, 10))
                .thenReturn(List.of(UserGroupBuyOrderDetailEntity.builder()
                        .userId("user-1")
                        .teamId("team-1")
                        .activityId(100201L)
                        .targetCount(10)
                        .tierSnapshot(JsonUtils.toJson(snapshotTiers))
                        .completeCount(10)
                        .lockCount(0)
                        .validStartTime(now)
                        .validEndTime(new Date(now.getTime() + 60_000L))
                        .build()));
        when(marketService.queryTeamStatisticByActivityId(100201L)).thenReturn(TeamStatisticVO.builder()
                .allTeamCount(1)
                .allTeamCompleteCount(0)
                .allTeamUserCount(10)
                .build());

        GoodsMarketRequestDTO request = new GoodsMarketRequestDTO();
        request.setUserId("1");
        request.setSource("s01");
        request.setChannel("c01");
        request.setGoodsId("9890002");

        Response<GoodsMarketResponseDTO> response = controller.queryGroupBuyMarketConfig(request);

        GoodsMarketResponseDTO.Team team = response.getData().getTeamList().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getData().getGoods().getDeductionPrice()));
        assertEquals(0, new BigDecimal("12.00").compareTo(response.getData().getGoods().getPayPrice()));
        assertEquals(Integer.valueOf(999), response.getData().getTiers().get(2).getBonusQuota());
        assertEquals(Integer.valueOf(18), team.getTiers().get(2).getBonusQuota());
        assertEquals(Integer.valueOf(3), team.getReachedTierNo());
        assertEquals(Integer.valueOf(10), team.getMaxTierTargetCount());
        assertNull(team.getNextTierTargetCount());
    }

    private GroupBuyActivityDiscountVO.Tier tier(int tierNo, int targetCount, int bonusQuota) {
        return GroupBuyActivityDiscountVO.Tier.builder()
                .tierNo(tierNo)
                .tierName(targetCount + "人团")
                .targetCount(targetCount)
                .bonusQuota(bonusQuota)
                .build();
    }

    private GoodsMarketResponseDTO.Tier responseTier(int tierNo, int targetCount, int bonusQuota) {
        return GoodsMarketResponseDTO.Tier.builder()
                .tierNo(tierNo)
                .tierName(targetCount + "人团")
                .targetCount(targetCount)
                .bonusQuota(bonusQuota)
                .build();
    }
}
