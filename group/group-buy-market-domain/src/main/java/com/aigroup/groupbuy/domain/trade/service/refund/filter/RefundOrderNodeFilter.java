package com.aigroup.groupbuy.domain.trade.service.refund.filter;

import com.aigroup.groupbuy.domain.trade.model.entity.*;
import com.aigroup.groupbuy.domain.trade.model.valobj.RefundTypeEnumVO;
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.domain.trade.service.refund.business.IRefundOrderStrategy;
import com.aigroup.groupbuy.domain.trade.service.refund.factory.TradeRefundRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.GroupBuyOrderEnumVO;
import cn.bugstack.wrench.design.framework.link.model2.handler.ILogicHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * 閫?鍗曡妭鐐?
 *
 * @author xiaofuge bugstack.cn @灏忓倕鍝?
 * 2025/7/30 10:31
 */
@Slf4j
@Service
public class RefundOrderNodeFilter implements ILogicHandler<TradeRefundCommandEntity, TradeRefundRuleFilterFactory.DynamicContext, TradeRefundBehaviorEntity> {

    @Resource
    private Map<String, IRefundOrderStrategy> refundOrderStrategyMap;

    @Override
    public TradeRefundBehaviorEntity apply(TradeRefundCommandEntity tradeRefundCommandEntity, TradeRefundRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("閫嗗悜娴佺▼-閫?鍗曟搷浣滐紝閫?鍗曠瓥鐣ュ鐞?userId:{} outTradeNo:{}", tradeRefundCommandEntity.getUserId(), tradeRefundCommandEntity.getOutTradeNo());

        // 涓婁笅鏂囨暟鎹?
        MarketPayOrderEntity marketPayOrderEntity = dynamicContext.getMarketPayOrderEntity();
        TradeOrderStatusEnumVO tradeOrderStatusEnumVO = marketPayOrderEntity.getTradeOrderStatusEnumVO();

        GroupBuyTeamEntity groupBuyTeamEntity = dynamicContext.getGroupBuyTeamEntity();
        GroupBuyOrderEnumVO groupBuyOrderEnumVO = groupBuyTeamEntity.getStatus();

        // 鑾峰彇鎵ц绛栫暐
        RefundTypeEnumVO refundType = RefundTypeEnumVO.getRefundStrategy(groupBuyOrderEnumVO, tradeOrderStatusEnumVO);
        IRefundOrderStrategy refundOrderStrategy = refundOrderStrategyMap.get(refundType.getStrategy());

        // 鎵ц閫?鍗曟搷浣?
        refundOrderStrategy.refundOrder(TradeRefundOrderEntity.builder()
                .userId(tradeRefundCommandEntity.getUserId())
                .orderId(marketPayOrderEntity.getOrderId())
                .teamId(marketPayOrderEntity.getTeamId())
                .activityId(groupBuyTeamEntity.getActivityId())
                .outTradeNo(tradeRefundCommandEntity.getOutTradeNo())
                .build());

        return TradeRefundBehaviorEntity.builder()
                .userId(tradeRefundCommandEntity.getUserId())
                .orderId(marketPayOrderEntity.getOrderId())
                .teamId(marketPayOrderEntity.getTeamId())
                .tradeRefundBehaviorEnum(TradeRefundBehaviorEntity.TradeRefundBehaviorEnum.SUCCESS)
                .build();
    }
}
