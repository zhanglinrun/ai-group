package com.aigroup.groupbuy.test.domain.trade;

import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundBehaviorEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundCommandEntity;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 閫嗗悜娴佺▼鍗曟祴
 *
 * @author xiaofuge bugstack.cn @灏忓倕鍝?
 * 2025/7/12 09:07
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ITradeRefundOrderServiceTest {

    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    @Test
    public void test_refundOrder() throws Exception {
        TradeRefundCommandEntity tradeRefundCommandEntity = TradeRefundCommandEntity.builder()
                .userId("xfg02")
                .outTradeNo("061974054911")
                .source("s01")
                .channel("c01")
                .build();

        TradeRefundBehaviorEntity tradeRefundBehaviorEntity = tradeRefundOrderService.refundOrder(tradeRefundCommandEntity);

        log.info("璇锋眰鍙傛暟:{}", JSON.toJSONString(tradeRefundCommandEntity));
        log.info("娴嬭瘯缁撴灉:{}", JSON.toJSONString(tradeRefundBehaviorEntity));

        // 鏆傚仠锛岀瓑寰匨Q娑堟伅銆傚鐞嗗畬鍚庯紝鎵嬪姩鍏抽棴绋嬪簭
        new CountDownLatch(1).await();
    }

    @Test
    public void test_refundOrder_01() throws Exception {
        TradeRefundCommandEntity tradeRefundCommandEntity = TradeRefundCommandEntity.builder()
                .userId("xfg04")
                .outTradeNo("727869517356")
                .source("s01")
                .channel("c01")
                .build();

        TradeRefundBehaviorEntity tradeRefundBehaviorEntity = tradeRefundOrderService.refundOrder(tradeRefundCommandEntity);

        log.info("璇锋眰鍙傛暟:{}", JSON.toJSONString(tradeRefundCommandEntity));
        log.info("娴嬭瘯缁撴灉:{}", JSON.toJSONString(tradeRefundBehaviorEntity));

        // 鏆傚仠锛岀瓑寰匨Q娑堟伅銆傚鐞嗗畬鍚庯紝鎵嬪姩鍏抽棴绋嬪簭
        new CountDownLatch(1).await();
    }

    @Test
    public void test_refundOrder_02() throws Exception {
        TradeRefundCommandEntity tradeRefundCommandEntity = TradeRefundCommandEntity.builder()
                .userId("xfg01")
                .outTradeNo("441842218120")
                .source("s01")
                .channel("c01")
                .build();

        TradeRefundBehaviorEntity tradeRefundBehaviorEntity = tradeRefundOrderService.refundOrder(tradeRefundCommandEntity);

        log.info("璇锋眰鍙傛暟:{}", JSON.toJSONString(tradeRefundCommandEntity));
        log.info("娴嬭瘯缁撴灉:{}", JSON.toJSONString(tradeRefundBehaviorEntity));

        // 鏆傚仠锛岀瓑寰匨Q娑堟伅銆傚鐞嗗畬鍚庯紝鎵嬪姩鍏抽棴绋嬪簭
        new CountDownLatch(1).await();
    }

    @Test
    public void test_refundOrder_03() throws Exception {
        TradeRefundCommandEntity tradeRefundCommandEntity = TradeRefundCommandEntity.builder()
                .userId("xfg02")
                .outTradeNo("061974054911")
                .source("s01")
                .channel("c01")
                .build();

        TradeRefundBehaviorEntity tradeRefundBehaviorEntity = tradeRefundOrderService.refundOrder(tradeRefundCommandEntity);

        log.info("璇锋眰鍙傛暟:{}", JSON.toJSONString(tradeRefundCommandEntity));
        log.info("娴嬭瘯缁撴灉:{}", JSON.toJSONString(tradeRefundBehaviorEntity));

        // 鏆傚仠锛岀瓑寰匨Q娑堟伅銆傚鐞嗗畬鍚庯紝鎵嬪姩鍏抽棴绋嬪簭
        new CountDownLatch(1).await();
    }

    @Test
    public void test_queryTimeoutUnpaidOrderList2Refund() throws Exception {
        List<UserGroupBuyOrderDetailEntity> timeoutOrderList = tradeRefundOrderService.queryTimeoutUnpaidOrderList();
        
        log.info("鏌ヨ瓒呮椂鏈敮浠樿鍗曞垪琛紝鏁伴噺锛歿}", timeoutOrderList != null ? timeoutOrderList.size() : 0);
        
        if (timeoutOrderList != null && !timeoutOrderList.isEmpty()) {
            for (UserGroupBuyOrderDetailEntity orderDetail : timeoutOrderList) {
                log.info("瓒呮椂璁㈠崟璇︽儏锛氱敤鎴稩D={}, 鍥㈤槦ID={}, 娲诲姩ID={}, 澶栭儴浜ゆ槗鍗曞彿={}, 鏈夋晥寮€濮嬫椂闂?{}, 鏈夋晥缁撴潫鏃堕棿={}", 
                        orderDetail.getUserId(), 
                        orderDetail.getTeamId(), 
                        orderDetail.getActivityId(), 
                        orderDetail.getOutTradeNo(),
                        orderDetail.getValidStartTime(),
                        orderDetail.getValidEndTime());

                TradeRefundCommandEntity tradeRefundCommandEntity = TradeRefundCommandEntity.builder()
                        .userId(orderDetail.getUserId())
                        .outTradeNo(orderDetail.getOutTradeNo())
                        .source(orderDetail.getSource())
                        .channel(orderDetail.getChannel())
                        .build();

                TradeRefundBehaviorEntity tradeRefundBehaviorEntity = tradeRefundOrderService.refundOrder(tradeRefundCommandEntity);

                log.info("璇锋眰鍙傛暟(job):{}", JSON.toJSONString(tradeRefundCommandEntity));
                log.info("娴嬭瘯缁撴灉(job):{}", JSON.toJSONString(tradeRefundBehaviorEntity));
            }
        } else {
            log.info("no timeout unpaid orders");
        }

        // 鏆傚仠锛岀瓑寰匨Q娑堟伅銆傚鐞嗗畬鍚庯紝鎵嬪姩鍏抽棴绋嬪簭
        new CountDownLatch(1).await();
    }

}
