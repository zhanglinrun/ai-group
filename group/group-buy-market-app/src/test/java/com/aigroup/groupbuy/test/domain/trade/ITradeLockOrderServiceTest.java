package com.aigroup.groupbuy.test.domain.trade;

import com.aigroup.groupbuy.domain.activity.model.entity.MarketProductEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.service.IIndexGroupBuyMarketService;
import com.aigroup.groupbuy.domain.trade.model.entity.MarketPayOrderEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.PayActivityEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.PayDiscountEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.UserEntity;
import com.aigroup.groupbuy.domain.trade.service.ITradeLockOrderService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 浜ゆ槗璁㈠崟鏈嶅姟娴嬭瘯
 * @create 2025-01-11 11:52
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ITradeLockOrderServiceTest {

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;

    @Resource
    private ITradeLockOrderService tradeOrderService;

    @Test
    public void test_lockMarketPayOrder() throws Exception {
        // 鍏ュ弬淇℃伅
        Long activityId = 100123L;
        String userId = "xiaofuge";
        String goodsId = "9890001";
        String source = "s01";
        String channel = "c01";
        String outTradeNo = "909000098111";

        // 1. 鑾峰彇璇曠畻浼樻儬锛屾湁銆恆ctivityId銆戜紭鍏堜娇鐢?
        TrialBalanceEntity trialBalanceEntity = indexGroupBuyMarketService.indexMarketTrial(MarketProductEntity.builder()
                .userId(userId)
                .source(source)
                .channel(channel)
                .goodsId(goodsId)
                .activityId(activityId)
                .build());

        GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = trialBalanceEntity.getGroupBuyActivityDiscountVO();

        // 鏌ヨ outTradeNo 鏄惁宸茬粡瀛樺湪浜ゆ槗璁板綍
        MarketPayOrderEntity marketPayOrderEntityOld = tradeOrderService.queryNoPayMarketPayOrderByOutTradeNo(userId, outTradeNo);
        if (null != marketPayOrderEntityOld) {
            log.info("娴嬭瘯缁撴灉(Old):{}", JSON.toJSONString(marketPayOrderEntityOld));
            return;
        }

        // 2. 閿佸畾锛岃惀閿?棰勬敮浠樿鍗曪紱鍟嗗搧涓嬪崟鍓嶏紝棰勮喘閿佸畾銆?
        MarketPayOrderEntity marketPayOrderEntityNew = tradeOrderService.lockMarketPayOrder(
                UserEntity.builder().userId(userId).build(),
                PayActivityEntity.builder()
                        .teamId(null)
                        .activityId(groupBuyActivityDiscountVO.getActivityId())
                        .activityName(groupBuyActivityDiscountVO.getActivityName())
                        .startTime(groupBuyActivityDiscountVO.getStartTime())
                        .endTime(groupBuyActivityDiscountVO.getEndTime())
                        .targetCount(groupBuyActivityDiscountVO.getTarget())
                        .build(),
                PayDiscountEntity.builder()
                        .source(source)
                        .channel(channel)
                        .goodsId(goodsId)
                        .goodsName(trialBalanceEntity.getGoodsName())
                        .originalPrice(trialBalanceEntity.getOriginalPrice())
                        .deductionPrice(trialBalanceEntity.getDeductionPrice())
                        .outTradeNo(outTradeNo)
                        .build());

        log.info("娴嬭瘯缁撴灉(New):{}",JSON.toJSONString(marketPayOrderEntityNew));
    }

}
