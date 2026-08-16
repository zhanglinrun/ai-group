package com.aigroup.groupbuy.test.domain.trade;

import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySettlementEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradePaySuccessEntity;
import com.aigroup.groupbuy.domain.trade.service.ITradeSettlementOrderService;
import com.aigroup.groupbuy.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.concurrent.CountDownLatch;

/**
 * @description 拼团交易结算服务测试
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
        tradePaySuccessEntity.setUserId("demo-user-01");
        tradePaySuccessEntity.setOutTradeNo("303596099292");
        tradePaySuccessEntity.setOutTradeTime(new Date());
        TradePaySettlementEntity tradePaySettlementEntity = tradeSettlementOrderService.settlementMarketPayOrder(tradePaySuccessEntity);
        log.info("请求参数:{}", JsonUtils.toJson(tradePaySuccessEntity));
        log.info("测试结果:{}", JsonUtils.toJson(tradePaySettlementEntity));

        // 暂停，等待MQ消息。处理完后，手动关闭程序
        new CountDownLatch(1).await();
    }

}
