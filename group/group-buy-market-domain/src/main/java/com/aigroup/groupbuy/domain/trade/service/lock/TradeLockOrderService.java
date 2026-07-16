package com.aigroup.groupbuy.domain.trade.service.lock;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import com.aigroup.groupbuy.domain.trade.model.entity.*;
import com.aigroup.groupbuy.domain.trade.model.valobj.GroupBuyProgressVO;
import com.aigroup.groupbuy.domain.trade.service.ITradeLockOrderService;
import com.aigroup.groupbuy.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 浜ゆ槗璁㈠崟鏈嶅姟
 * @create 2025-01-11 08:07
 */
@Slf4j
@Service
public class TradeLockOrderService implements ITradeLockOrderService {

    @Resource
    private ITradeRepository repository;
    @Resource
    private BusinessLinkedList<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> tradeRuleFilter;

    @Override
    public MarketPayOrderEntity queryNoPayMarketPayOrderByOutTradeNo(String userId, String outTradeNo) {
        log.info("鎷煎洟浜ゆ槗-鏌ヨ鏈敮浠樿惀閿?璁㈠崟:{} outTradeNo:{}", userId, outTradeNo);
        return repository.queryMarketPayOrderEntityByOutTradeNo(userId, outTradeNo);
    }

    @Override
    public MarketPayOrderEntity queryMarketPayOrderByBusinessKey(String userId, String source, String channel,
                                                                 String outTradeNo) {
        log.info("query group lock result userId:{} source:{} channel:{} outTradeNo:{}",
                userId, source, channel, outTradeNo);
        return repository.queryMarketPayOrderEntityByBusinessKey(userId, source, channel, outTradeNo);
    }

    @Override
    public GroupBuyProgressVO queryGroupBuyProgress(String teamId) {
        log.info("鎷煎洟浜ゆ槗-鏌ヨ鎷煎崟杩涘害:{}", teamId);
        return repository.queryGroupBuyProgress(teamId);
    }

    @Override
    public MarketPayOrderEntity lockMarketPayOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception {
        log.info("鎷煎洟浜ゆ槗-閿佸畾钀ラ攢浼樻儬鏀粯璁㈠崟:{} activityId:{} goodsId:{}", userEntity.getUserId(), payActivityEntity.getActivityId(), payDiscountEntity.getGoodsId());

        // 浜ゆ槗瑙勫垯杩囨护
        TradeLockRuleFilterBackEntity tradeLockRuleFilterBackEntity = tradeRuleFilter.apply(TradeLockRuleCommandEntity.builder()
                        .activityId(payActivityEntity.getActivityId())
                        .userId(userEntity.getUserId())
                        .teamId(payActivityEntity.getTeamId())
                        .build(),
                new TradeLockRuleFilterFactory.DynamicContext());

        // 宸插弬涓庢嫾鍥㈤噺 - 鐢ㄤ簬鏋勫缓鏁版嵁搴撳敮涓?绱㈠紩浣跨敤锛岀‘淇濈敤鎴峰彧鑳藉湪涓?涓椿鍔ㄤ笂鍙備笌鍥哄畾鐨勬鏁?
        Integer userTakeOrderCount = tradeLockRuleFilterBackEntity.getUserTakeOrderCount();

        // 鏋勫缓鑱氬悎瀵硅薄
        GroupBuyOrderAggregate groupBuyOrderAggregate = GroupBuyOrderAggregate.builder()
                .userEntity(userEntity)
                .payActivityEntity(payActivityEntity)
                .payDiscountEntity(payDiscountEntity)
                .userTakeOrderCount(userTakeOrderCount)
                .build();

        try {
            // 閿佸畾鑱氬悎璁㈠崟 - 杩欎細鐢ㄦ埛鍙槸涓嬪崟杩樻病鏈夋敮浠樸?傚悗缁細鏈?涓祦绋嬶紱鏀粯鎴愬姛銆佽秴鏃舵湭鏀粯锛堝洖閫?锛?
            return repository.lockMarketPayOrder(groupBuyOrderAggregate);
        } catch (Exception e) {
            // 璁板綍澶辫触鎭㈠閲?
            repository.recoveryTeamStock(tradeLockRuleFilterBackEntity.getRecoveryTeamStockKey(), payActivityEntity.getValidTime());
            throw e;
        }

    }

}
