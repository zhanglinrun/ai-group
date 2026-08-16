package com.aigroup.groupbuy.domain.trade.service.settlement.filter;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyTeamEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.MarketPayOrderEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeSettlementRuleFilterBackEntity;
import com.aigroup.groupbuy.domain.trade.service.settlement.factory.TradeSettlementRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.GroupBuyOrderEnumVO;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import cn.bugstack.wrench.design.framework.link.model2.handler.ILogicHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Date;

/**
 * @description 拼团交易结算可执行规则过滤器
 * @create 2025-01-29 09:38
 */
@Slf4j
@Service
public class SettableRuleFilter implements ILogicHandler<TradeSettlementRuleCommandEntity, TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeSettlementRuleFilterBackEntity apply(TradeSettlementRuleCommandEntity requestParameter, TradeSettlementRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("settlement rule filter - settable check, userId:{} outTradeNo:{}", requestParameter.getUserId(), requestParameter.getOutTradeNo());

        // 获取支付订单上下文
        MarketPayOrderEntity marketPayOrderEntity = dynamicContext.getMarketPayOrderEntity();

        // 查询拼团队伍
        GroupBuyTeamEntity groupBuyTeamEntity = repository.queryGroupBuyTeamByTeamId(marketPayOrderEntity.getTeamId());

        // B2: reject settlement early when the team already reached a terminal state
        // (COMPLETE/FAIL/COMPLETE_FAIL). Without this check the pay-success callback of a
        // failed team would enter the settlement transaction and roll back with the
        // unrecognizable UPDATE_ZERO, leaving the paid order stuck forever.
        if (!GroupBuyOrderEnumVO.PROGRESS.equals(groupBuyTeamEntity.getStatus())) {
            log.error("settlement rejected, team is finalized. teamId:{} status:{} userId:{} outTradeNo:{}",
                    groupBuyTeamEntity.getTeamId(), groupBuyTeamEntity.getStatus(), requestParameter.getUserId(), requestParameter.getOutTradeNo());
            throw new AppException(ResponseCode.E0107);
        }

        // 获取外部交易时间，用于校验支付是否发生在拼团有效期内
        Date outTradeTime = requestParameter.getOutTradeTime();

        // 交易时间不得晚于或等于拼团截止时间
        if (!outTradeTime.before(groupBuyTeamEntity.getValidEndTime())) {
            log.error("order trade time outside group valid window");
            throw new AppException(ResponseCode.E0106);
        }

        // 将拼团队伍保存到动态上下文
        dynamicContext.setGroupBuyTeamEntity(groupBuyTeamEntity);

        return next(requestParameter, dynamicContext);
    }

}
