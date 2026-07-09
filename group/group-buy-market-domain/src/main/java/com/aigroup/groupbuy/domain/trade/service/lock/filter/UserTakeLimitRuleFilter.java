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
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 鐢ㄦ埛鍙備笌闄愬埗锛岃鍒欒繃婊?
 * @create 2025-01-25 09:19
 */
@Slf4j
@Service
public class UserTakeLimitRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity requestParameter, TradeLockRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("浜ゆ槗瑙勫垯杩囨护-鐢ㄦ埛鍙備笌娆℃暟鏍￠獙{} activityId:{}", requestParameter.getUserId(), requestParameter.getActivityId());

        GroupBuyActivityEntity groupBuyActivity = dynamicContext.getGroupBuyActivity();

        // 鏌ヨ鐢ㄦ埛鍦ㄤ竴涓嫾鍥㈡椿鍔ㄤ笂鍙備笌鐨勬鏁?
        Integer count = repository.queryOrderCountByActivityId(requestParameter.getActivityId(), requestParameter.getUserId());

        if (null != groupBuyActivity.getTakeLimitCount() && count >= groupBuyActivity.getTakeLimitCount()) {
            log.info("鐢ㄦ埛鍙備笌娆℃暟鏍￠獙锛屽凡杈惧彲鍙備笌涓婇檺 activityId:{}", requestParameter.getActivityId());
            throw new AppException(ResponseCode.E0103);
        }

        dynamicContext.setUserTakeOrderCount(count);

        // 璧板埌涓嬩竴涓矗浠婚摼鑺傜偣
        return next(requestParameter, dynamicContext);
    }

}
