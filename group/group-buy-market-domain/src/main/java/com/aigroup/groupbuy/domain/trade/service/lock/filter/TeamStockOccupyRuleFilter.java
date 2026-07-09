package com.aigroup.groupbuy.domain.trade.service.lock.filter;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyActivityEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.aigroup.groupbuy.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.wrench.design.framework.link.model2.handler.ILogicHandler;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 缁勯槦搴撳瓨鍗犵敤瑙勫垯杩囨护
 * @create 2025-04-05 09:41
 */
@Slf4j
@Service
public class TeamStockOccupyRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity requestParameter, TradeLockRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("浜ゆ槗瑙勫垯杩囨护-缁勯槦搴撳瓨鏍￠獙{} activityId:{}", requestParameter.getUserId(), requestParameter.getActivityId());

        // 1. teamId 涓虹┖锛屽垯涓洪娆″紑鍥紝涓嶅仛鎷煎洟缁勯槦鐩爣閲忓簱瀛橀檺鍒?
        String teamId = requestParameter.getTeamId();
        if (StringUtils.isBlank(teamId)) {
            return TradeLockRuleFilterBackEntity.builder()
                    .userTakeOrderCount(dynamicContext.getUserTakeOrderCount())
                    .build();
        }

        // 2. 鎶㈠崰搴撳瓨锛涢?氳繃鎶㈠崰 Redis 缂撳瓨搴撳瓨锛屾潵闄嶄綆瀵规暟鎹簱鐨勬搷浣滃帇鍔涖??
        GroupBuyActivityEntity groupBuyActivity = dynamicContext.getGroupBuyActivity();
        Integer target = groupBuyActivity.getTarget();
        Integer validTime = groupBuyActivity.getValidTime();
        String teamStockKey = dynamicContext.generateTeamStockKey(teamId);
        String recoveryTeamStockKey = dynamicContext.generateRecoveryTeamStockKey(teamId);

        boolean status = repository.occupyTeamStock(teamStockKey, recoveryTeamStockKey, target, validTime);

        if (!status) {
            log.warn("浜ゆ槗瑙勫垯杩囨护-缁勯槦搴撳瓨鏍￠獙{} activityId:{} 鎶㈠崰澶辫触:{}", requestParameter.getUserId(), requestParameter.getActivityId(), teamStockKey);
            throw new AppException(ResponseCode.E0008);
        }

        return TradeLockRuleFilterBackEntity.builder()
                .userTakeOrderCount(dynamicContext.getUserTakeOrderCount())
                .recoveryTeamStockKey(recoveryTeamStockKey)
                .build();
    }

}
