package com.aigroup.groupbuy.domain.activity.service.trial.node;

import com.aigroup.groupbuy.domain.activity.model.entity.MarketProductEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.service.trial.AbstractGroupBuyMarketSupport;
import com.aigroup.groupbuy.domain.activity.service.trial.factory.DefaultActivityStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 寮?鍏宠妭鐐?
 * @create 2024-12-14 14:27
 */
@Slf4j
@Service
public class SwitchNode extends AbstractGroupBuyMarketSupport<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> {

    @Resource
    private MarketNode marketNode;

    @Override
    public TrialBalanceEntity doApply(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("鎷煎洟鍟嗗搧鏌ヨ璇曠畻鏈嶅姟-SwitchNode userId:{} requestParameter:{}", requestParameter.getUserId(), JSON.toJSONString(requestParameter));

        // 鏍规嵁鐢ㄦ埛ID鍒囬噺
        String userId = requestParameter.getUserId();

        // 鍒ゆ柇鏄惁闄嶇骇
        if (repository.downgradeSwitch()) {
            log.info("鎷煎洟娲诲姩闄嶇骇鎷︽埅 {}", userId);
            throw new AppException(ResponseCode.E0003.getCode(), ResponseCode.E0003.getInfo());
        }

        // 鍒囬噺鑼冨洿鍒ゆ柇
        if (!repository.cutRange(userId)) {
            log.info("鎷煎洟娲诲姩鍒囬噺鎷︽埅 {}", userId);
            throw new AppException(ResponseCode.E0004.getCode(), ResponseCode.E0004.getInfo());
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> get(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return marketNode;
    }

}
