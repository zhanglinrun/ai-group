package com.aigroup.groupbuy.test.domain.trade;

import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySettlementEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySuccessEntity;
import com.aigroup.groupbuy.domain.trade.service.ITradeSettlementOrderService;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.concurrent.CountDownLatch;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 鎷煎洟浜ゆ槗缁撶畻鏈嶅姟娴嬭瘯
 * @create 2025-01-26 18:59
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class TradeSettlementOrderServiceTest {

    @Resource
    private ITradeSettlementOrderService tradeSettlementOrderService;

    @Test
    public void test_settlementMarketPayOrder() throws Exception {
        TradePaySuccessEntity tradePaySuccessEntity = new TradePaySuccessEntity();
        tradePaySuccessEntity.setSource("s01");
        tradePaySuccessEntity.setChannel("c01");
        tradePaySuccessEntity.setUserId("xfg01");
        tradePaySuccessEntity.setOutTradeNo("303596099292");
        tradePaySuccessEntity.setOutTradeTime(new Date());
        TradePaySettlementEntity tradePaySettlementEntity = tradeSettlementOrderService.settlementMarketPayOrder(tradePaySuccessEntity);
        log.info("璇锋眰鍙傛暟:{}", JSON.toJSONString(tradePaySuccessEntity));
        log.info("娴嬭瘯缁撴灉:{}", JSON.toJSONString(tradePaySettlementEntity));

        // 鏆傚仠锛岀瓑寰匨Q娑堟伅銆傚鐞嗗畬鍚庯紝鎵嬪姩鍏抽棴绋嬪簭
        new CountDownLatch(1).await();
    }

}
