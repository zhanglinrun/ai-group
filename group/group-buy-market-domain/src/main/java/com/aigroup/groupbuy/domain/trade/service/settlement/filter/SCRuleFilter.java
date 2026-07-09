package com.aigroup.groupbuy.domain.trade.service.settlement.filter;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleFilterBackEntity;
import com.aigroup.groupbuy.domain.trade.service.settlement.factory.TradeSettlementRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import cn.bugstack.wrench.design.framework.link.model2.handler.ILogicHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description SC 娓犻亾鏉ユ簮杩囨护 - 褰撴煇涓绾︽笭閬撲笅鏋跺悗锛屽垯涓嶄細璁拌处
 * @create 2025-01-29 09:16
 */
@Slf4j
@Service
public class SCRuleFilter implements ILogicHandler<TradeSettlementRuleCommandEntity, TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeSettlementRuleFilterBackEntity apply(TradeSettlementRuleCommandEntity requestParameter, TradeSettlementRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("缁撶畻瑙勫垯杩囨护-娓犻亾榛戝悕鍗曟牎楠寋} outTradeNo:{}", requestParameter.getUserId(), requestParameter.getOutTradeNo());

        // sc 娓犻亾榛戝悕鍗曟嫤鎴?
        boolean intercept = repository.isSCBlackIntercept(requestParameter.getSource(), requestParameter.getChannel());
        if (intercept) {
            log.error("channel blacklisted source={} channel={}", requestParameter.getSource(), requestParameter.getChannel());
            throw new AppException(ResponseCode.E0105);
        }

        return next(requestParameter, dynamicContext);
    }

}
