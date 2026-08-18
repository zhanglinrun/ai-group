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
import com.aigroup.groupbuy.trigger.http.support.GatewayUserBinder;
import com.aigroup.groupbuy.types.annotations.RateLimiterAccessInterceptor;
import com.aigroup.groupbuy.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @description 拼团市场首页接口
 * @create 2025-02-02 16:03
 */
@Slf4j
@RestController()
@RequestMapping({"/api/v1/gbm/index/", "/api/group/"})
public class MarketIndexController implements IMarketIndexService {

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;

    @RateLimiterAccessInterceptor(key = "userId", fallbackMethod = "queryGroupBuyMarketConfigFallBack",
            permitsPerSecond = 1.0d, blacklistCount = 1)
    @RequestMapping(value = {"query_group_buy_market_config", "activities"}, method = RequestMethod.POST)
    @Override
    public Response<GoodsMarketResponseDTO> queryGroupBuyMarketConfig(@RequestBody GoodsMarketRequestDTO requestDTO) {
        try {
            requestDTO.setUserId(GatewayUserBinder.requireUserId(requestDTO.getUserId()));
            log.info("查询拼团市场配置，userId:{} goodsId:{}", requestDTO.getUserId(), requestDTO.getGoodsId());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel()) || StringUtils.isBlank(requestDTO.getGoodsId())) {
                return Response.<GoodsMarketResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. 查询商品拼团试算结果
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

            // 3. 查询活动拼团统计
            TeamStatisticVO teamStatisticVO = indexGroupBuyMarketService.queryTeamStatisticByActivityId(activityId);

            GoodsMarketResponseDTO.Goods goods = GoodsMarketResponseDTO.Goods.builder()
                    .goodsId(trialBalanceEntity.getGoodsId())
                    .originalPrice(trialBalanceEntity.getOriginalPrice())
                    .deductionPrice(trialBalanceEntity.getDeductionPrice())
                    .payPrice(trialBalanceEntity.getPayPrice())
                    .build();

            List<GoodsMarketResponseDTO.Team> teams = new ArrayList<>();
            if (null != userGroupBuyOrderDetailEntities && !userGroupBuyOrderDetailEntities.isEmpty()) {
                for (UserGroupBuyOrderDetailEntity userGroupBuyOrderDetailEntity : userGroupBuyOrderDetailEntities) {
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
                            .targetCount(trialBalanceEntity.getTargetCount())
                            .goods(goods)
                            .teamList(teams)
                            .teamStatistic(teamStatistic)
                            .build())
                    .build();

            log.info("拼团市场配置查询成功，userId:{} goodsId:{} response:{}", requestDTO.getUserId(), requestDTO.getGoodsId(), JsonUtils.toJson(response));

            return response;
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("拼团市场配置查询拒绝: {}", e.getMessage());
            return Response.<GoodsMarketResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("拼团市场配置查询失败，userId:{} goodsId:{}", requestDTO.getUserId(), requestDTO.getGoodsId(), e);
            return Response.<GoodsMarketResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    public Response<GoodsMarketResponseDTO> queryGroupBuyMarketConfigFallBack(@RequestBody GoodsMarketRequestDTO requestDTO) {
        log.error("拼团市场配置查询触发限流降级，userId:{}", requestDTO.getUserId());
        return Response.<GoodsMarketResponseDTO>builder()
                .code(ResponseCode.RATE_LIMITER.getCode())
                .info(ResponseCode.RATE_LIMITER.getInfo())
                .build();
    }

    /** Browser/BFF-friendly canonical read variant; POST remains for legacy Feign callers. */
    @GetMapping("activities")
    public Response<GoodsMarketResponseDTO> listActivities(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "s01") String source,
            @RequestParam(defaultValue = "c01") String channel,
            @RequestParam(defaultValue = "9890002") String goodsId) {
        GoodsMarketRequestDTO request = new GoodsMarketRequestDTO();
        request.setUserId(userId);
        request.setSource(source);
        request.setChannel(channel);
        request.setGoodsId(goodsId);
        return queryGroupBuyMarketConfig(request);
    }

}
