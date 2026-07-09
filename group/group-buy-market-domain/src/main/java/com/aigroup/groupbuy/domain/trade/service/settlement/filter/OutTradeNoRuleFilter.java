package com.aigroup.groupbuy.domain.trade.service.settlement.filter;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.MarketPayOrderEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleFilterBackEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.domain.trade.service.settlement.factory.TradeSettlementRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import cn.bugstack.wrench.design.framework.link.model2.handler.ILogicHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 澶栭儴浜ゆ槗鍗曞彿杩囨护锛涘閮ㄤ氦鏄撳崟鍙锋槸鍚︿负閫?鍗?
 * @create 2025-01-29 09:37
 */
@Slf4j
@Service
public class OutTradeNoRuleFilter implements ILogicHandler<TradeSettlementRuleCommandEntity, TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeSettlementRuleFilterBackEntity apply(TradeSettlementRuleCommandEntity requestParameter, TradeSettlementRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("缁撶畻瑙勫垯杩囨护-澶栭儴鍗曞彿鏍￠獙{} outTradeNo:{}", requestParameter.getUserId(), requestParameter.getOutTradeNo());

        // 鏌ヨ鎷煎洟淇℃伅
        MarketPayOrderEntity marketPayOrderEntity = repository.queryMarketPayOrderEntityByOutTradeNo(requestParameter.getUserId(), requestParameter.getOutTradeNo());

        if (null == marketPayOrderEntity || TradeOrderStatusEnumVO.CLOSE.equals(marketPayOrderEntity.getTradeOrderStatusEnumVO())) {
            log.error("涓嶅瓨鍦ㄧ殑澶栭儴浜ゆ槗鍗曞彿鎴栫敤鎴峰凡閫?鍗曪紝涓嶉渶瑕佸仛鏀粯璁㈠崟缁撶畻:{} outTradeNo:{}", requestParameter.getUserId(), requestParameter.getOutTradeNo());
            throw new AppException(ResponseCode.E0104);
        }

        dynamicContext.setMarketPayOrderEntity(marketPayOrderEntity);

        return next(requestParameter, dynamicContext);
    }

}
