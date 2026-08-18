package com.aigroup.groupbuy.domain.trade.service;

import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySettlementEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySuccessEntity;

import java.util.Map;

/**
 * @description 拼团交易结算服务接口
 * @create 2025-01-26 14:34
 */
public interface ITradeSettlementOrderService {

    /**
     * 营销结算
     *
     * @param tradePaySuccessEntity 交易支付订单实体对象
     * @return 交易结算订单实体
     */
    TradePaySettlementEntity settlementMarketPayOrder(TradePaySuccessEntity tradePaySuccessEntity) throws Exception;

    /** Dev-only orchestration. The HTTP mapping that calls this method is absent outside dev. */
    String finalizePaidTeamForDemo(String userId, String outTradeNo) throws Exception;

}
