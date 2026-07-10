package com.aigroup.groupbuy.domain.trade.service;

import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySettlementEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySuccessEntity;

import java.util.Map;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
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

    /**
     * 阶梯拼团到期结算：对到期已达最低档的团按已达档位定档发放（写入成团回调任务，由通知任务派发）。
     *
     * @return 本轮成功结算的团数量
     */
    int settleExpiredFormedTeams();

}
