package com.aigroup.groupbuy.domain.trade.service.lock.filter;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyActivityEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyTeamEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.aigroup.groupbuy.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.wrench.design.framework.link.model2.handler.ILogicHandler;
import com.aigroup.groupbuy.types.enums.GroupBuyOrderEnumVO;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 组队库存占用规则过滤
 * @create 2025-04-05 09:41
 */
@Slf4j
@Service
public class TeamStockOccupyRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity requestParameter, TradeLockRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("交易规则过滤-组队库存校验{} activityId:{}", requestParameter.getUserId(), requestParameter.getActivityId());

        // 1. teamId 为空，则为首次开团，不做拼团组队目标量库存限制
        String teamId = requestParameter.getTeamId();
        if (StringUtils.isBlank(teamId)) {
            return TradeLockRuleFilterBackEntity.builder()
                    .userTakeOrderCount(dynamicContext.getUserTakeOrderCount())
                    .build();
        }

        // Joining an existing team must use the team's immutable creation snapshot.
        // Never trust a client-supplied teamId without checking its activity: otherwise
        // an order for one quota SKU can be attached to another SKU's team and receive
        // the wrong tier bonus. Terminal/expired teams are rejected before Redis/DB
        // mutation; the SQL update keeps the same guards for the race window.
        GroupBuyActivityEntity groupBuyActivity = dynamicContext.getGroupBuyActivity();
        GroupBuyTeamEntity team = repository.queryGroupBuyTeamByTeamId(teamId);
        if (team == null || !Objects.equals(groupBuyActivity.getActivityId(), team.getActivityId())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "team does not belong to the requested activity");
        }
        if (!GroupBuyOrderEnumVO.PROGRESS.equals(team.getStatus())) {
            throw new AppException(ResponseCode.E0107);
        }
        Date now = new Date();
        if (team.getValidEndTime() == null || !now.before(team.getValidEndTime())) {
            throw new AppException(ResponseCode.E0106.getCode(), "group buy team has expired");
        }
        if (team.getTargetCount() == null || team.getLockCount() == null
                || team.getLockCount() >= team.getTargetCount()) {
            throw new AppException(ResponseCode.E0006);
        }

        Integer target = team.getTargetCount();
        long remainingMillis = team.getValidEndTime().getTime() - now.getTime();
        Integer validTime = Math.max(1, (int) Math.ceil((double) remainingMillis / TimeUnit.MINUTES.toMillis(1)));
        String teamStockKey = dynamicContext.generateTeamStockKey(teamId);
        String recoveryTeamStockKey = dynamicContext.generateRecoveryTeamStockKey(teamId);

        boolean status = repository.occupyTeamStock(teamStockKey, recoveryTeamStockKey, target, validTime);

        if (!status) {
            log.warn("交易规则过滤-组队库存校验{} activityId:{} 抢占失败:{}", requestParameter.getUserId(), requestParameter.getActivityId(), teamStockKey);
            throw new AppException(ResponseCode.E0008);
        }

        return TradeLockRuleFilterBackEntity.builder()
                .userTakeOrderCount(dynamicContext.getUserTakeOrderCount())
                .recoveryTeamStockKey(recoveryTeamStockKey)
                .build();
    }

}
