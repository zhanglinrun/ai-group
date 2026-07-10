package com.aigroup.groupbuy.trigger.http;

import com.aigroup.groupbuy.api.IMarketIndexService;
import com.aigroup.groupbuy.api.dto.GoodsMarketRequestDTO;
import com.aigroup.groupbuy.api.dto.GoodsMarketResponseDTO;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.domain.activity.model.entity.MarketProductEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.TeamStatisticVO;
import com.aigroup.groupbuy.domain.activity.service.IIndexGroupBuyMarketService;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author Fuzhengwei bugstack.cn @????
 * @description ??????
 * @create 2025-02-02 16:03
 */
@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/gbm/index/")
public class MarketIndexController implements IMarketIndexService {

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;

    @RequestMapping(value = "query_group_buy_market_config", method = RequestMethod.POST)
    @Override
    public Response<GoodsMarketResponseDTO> queryGroupBuyMarketConfig(@RequestBody GoodsMarketRequestDTO requestDTO) {
        try {
            log.info("???????????{} goodsId:{}", requestDTO.getUserId(), requestDTO.getGoodsId());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel()) || StringUtils.isBlank(requestDTO.getGoodsId())) {
                return Response.<GoodsMarketResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. ??????
            TrialBalanceEntity trialBalanceEntity = indexGroupBuyMarketService.indexMarketTrial(MarketProductEntity.builder()
                    .userId(requestDTO.getUserId())
                    .source(requestDTO.getSource())
                    .channel(requestDTO.getChannel())
                    .goodsId(requestDTO.getGoodsId())
                    .build());


            GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = trialBalanceEntity.getGroupBuyActivityDiscountVO();
            Long activityId = groupBuyActivityDiscountVO.getActivityId();

            // 2. 查询进行中的拼团队伍（本人置顶 3 + 其他人随机 10）。
            // 原采样 1+2 会让新开的团大概率不出现在大厅（用户反馈"拼团后大厅没显示"），
            // 调大采样数保证自己开的团必现、他人团有足够曝光。
            List<UserGroupBuyOrderDetailEntity> userGroupBuyOrderDetailEntities = indexGroupBuyMarketService.queryInProgressUserGroupBuyOrderDetailList(activityId, requestDTO.getUserId(), 3, 10);

            // 3. ??????
            TeamStatisticVO teamStatisticVO = indexGroupBuyMarketService.queryTeamStatisticByActivityId(activityId);

            GoodsMarketResponseDTO.Goods goods = GoodsMarketResponseDTO.Goods.builder()
                    .goodsId(trialBalanceEntity.getGoodsId())
                    .originalPrice(trialBalanceEntity.getOriginalPrice())
                    .deductionPrice(trialBalanceEntity.getDeductionPrice())
                    .payPrice(trialBalanceEntity.getPayPrice())
                    .build();

            // 阶梯档位（人数 → 累计加赠额度），仅阶梯额度拼团有值
            List<GroupBuyActivityDiscountVO.Tier> voTiers = groupBuyActivityDiscountVO.getTiers();
            List<GoodsMarketResponseDTO.Tier> tiers = new ArrayList<>();
            Integer maxTierTargetCount = null;
            if (null != voTiers && !voTiers.isEmpty()) {
                for (GroupBuyActivityDiscountVO.Tier voTier : voTiers) {
                    tiers.add(GoodsMarketResponseDTO.Tier.builder()
                            .tierNo(voTier.getTierNo())
                            .tierName(voTier.getTierName())
                            .targetCount(voTier.getTargetCount())
                            .bonusQuota(voTier.getBonusQuota())
                            .build());
                    if (null != voTier.getTargetCount() && (null == maxTierTargetCount || voTier.getTargetCount() > maxTierTargetCount)) {
                        maxTierTargetCount = voTier.getTargetCount();
                    }
                }
            }

            List<GoodsMarketResponseDTO.Team> teams = new ArrayList<>();
            if (null != userGroupBuyOrderDetailEntities && !userGroupBuyOrderDetailEntities.isEmpty()) {
                for (UserGroupBuyOrderDetailEntity userGroupBuyOrderDetailEntity : userGroupBuyOrderDetailEntities) {
                    // 阶梯额度拼团：按当前完成人数计算已达档位与下一档位
                    int completeCount = null != userGroupBuyOrderDetailEntity.getCompleteCount() ? userGroupBuyOrderDetailEntity.getCompleteCount() : 0;
                    Integer reachedTierNo = 0;
                    Integer nextTierTargetCount = null;
                    if (null != voTiers && !voTiers.isEmpty()) {
                        for (GroupBuyActivityDiscountVO.Tier voTier : voTiers) {
                            if (null == voTier.getTargetCount()) continue;
                            if (completeCount >= voTier.getTargetCount()) {
                                reachedTierNo = voTier.getTierNo();
                            } else if (null == nextTierTargetCount) {
                                nextTierTargetCount = voTier.getTargetCount();
                            }
                        }
                    }
                    GoodsMarketResponseDTO.Team team = GoodsMarketResponseDTO.Team.builder()
                            .userId(userGroupBuyOrderDetailEntity.getUserId())
                            .teamId(userGroupBuyOrderDetailEntity.getTeamId())
                            .activityId(userGroupBuyOrderDetailEntity.getActivityId())
                            .targetCount(userGroupBuyOrderDetailEntity.getTargetCount())
                            .completeCount(userGroupBuyOrderDetailEntity.getCompleteCount())
                            .lockCount(userGroupBuyOrderDetailEntity.getLockCount())
                            .validStartTime(userGroupBuyOrderDetailEntity.getValidStartTime())
                            .validEndTime(userGroupBuyOrderDetailEntity.getValidEndTime())
                            .validTimeCountdown(GoodsMarketResponseDTO.Team.differenceDateTime2Str(new Date(), userGroupBuyOrderDetailEntity.getValidEndTime()))
                            .outTradeNo(userGroupBuyOrderDetailEntity.getOutTradeNo())
                            .reachedTierNo(reachedTierNo)
                            .nextTierTargetCount(nextTierTargetCount)
                            .maxTierTargetCount(maxTierTargetCount)
                            .build();
                    teams.add(team);
                }
            }

            GoodsMarketResponseDTO.TeamStatistic teamStatistic = GoodsMarketResponseDTO.TeamStatistic.builder()
                    .allTeamCount(teamStatisticVO.getAllTeamCount())
                    .allTeamCompleteCount(teamStatisticVO.getAllTeamCompleteCount())
                    .allTeamUserCount(teamStatisticVO.getAllTeamUserCount())
                    .build();

            Response<GoodsMarketResponseDTO> response = Response.<GoodsMarketResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(GoodsMarketResponseDTO.builder()
                            .activityId(activityId)
                            .activityType(groupBuyActivityDiscountVO.getActivityType())
                            .goods(goods)
                            .tiers(tiers)
                            .teamList(teams)
                            .teamStatistic(teamStatistic)
                            .build())
                    .build();

            log.info("??????????:{} goodsId:{} response:{}", requestDTO.getUserId(), requestDTO.getGoodsId(), JSON.toJSONString(response));

            return response;
        } catch (Exception e) {
            log.error("??????????:{} goodsId:{}", requestDTO.getUserId(), requestDTO.getGoodsId(), e);
            return Response.<GoodsMarketResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    public Response<GoodsMarketResponseDTO> queryGroupBuyMarketConfigFallBack(@RequestBody GoodsMarketRequestDTO requestDTO) {
        log.error("??????????:{}", requestDTO.getUserId());
        return Response.<GoodsMarketResponseDTO>builder()
                .code(ResponseCode.RATE_LIMITER.getCode())
                .info(ResponseCode.RATE_LIMITER.getInfo())
                .build();
    }

}
