package com.aigroup.groupbuy.test.domain.trade;

import com.aigroup.groupbuy.api.IMarketTradeService;
import com.aigroup.groupbuy.api.dto.LockMarketPayOrderRequestDTO;
import com.aigroup.groupbuy.api.dto.LockMarketPayOrderResponseDTO;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundBehaviorEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundCommandEntity;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.concurrent.CountDownLatch;

/**
 * 閿佸崟銆佹仮澶嶃?侀攣鍗?
 *
 * @author xiaofuge bugstack.cn @灏忓倕鍝?
 * 2025/7/29 10:34
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ITradeReverseStockServiceTest {

    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    @Resource
    private IMarketTradeService marketTradeService;

    @Test
    public void test_refundOrder() throws Exception {
        TradeRefundCommandEntity tradeRefundCommandEntity = TradeRefundCommandEntity.builder()
                .userId("xfg803")
                .outTradeNo("356654963071")
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
    public void test_lockMarketPayOrder() throws InterruptedException {
        String teamId = null;
        for (int i = 1; i < 4; i++) {
            LockMarketPayOrderRequestDTO lockMarketPayOrderRequestDTO = new LockMarketPayOrderRequestDTO();
            lockMarketPayOrderRequestDTO.setUserId("xfg110" + i);
            lockMarketPayOrderRequestDTO.setTeamId(teamId);
            lockMarketPayOrderRequestDTO.setActivityId(100123L);
            lockMarketPayOrderRequestDTO.setGoodsId("9890001");
            lockMarketPayOrderRequestDTO.setSource("s01");
            lockMarketPayOrderRequestDTO.setChannel("c01");
            lockMarketPayOrderRequestDTO.setNotifyMQ();
            lockMarketPayOrderRequestDTO.setOutTradeNo(RandomStringUtils.randomNumeric(12));

            Response<LockMarketPayOrderResponseDTO> lockMarketPayOrderResponseDTOResponse = marketTradeService.lockMarketPayOrder(lockMarketPayOrderRequestDTO);
            teamId = lockMarketPayOrderResponseDTOResponse.getData().getTeamId();

            log.info("绗瑊}绗旓紝娴嬭瘯缁撴灉 req:{} res:{}", i, JSON.toJSONString(lockMarketPayOrderRequestDTO), JSON.toJSONString(lockMarketPayOrderResponseDTOResponse));
        }

    }

    @Test
    public void test_lockMarketPayOrder_reverse() throws InterruptedException {
        LockMarketPayOrderRequestDTO lockMarketPayOrderRequestDTO = new LockMarketPayOrderRequestDTO();
        lockMarketPayOrderRequestDTO.setUserId("xfg804");
        lockMarketPayOrderRequestDTO.setTeamId("23165018");
        lockMarketPayOrderRequestDTO.setActivityId(100123L);
        lockMarketPayOrderRequestDTO.setGoodsId("9890001");
        lockMarketPayOrderRequestDTO.setSource("s01");
        lockMarketPayOrderRequestDTO.setChannel("c01");
        lockMarketPayOrderRequestDTO.setNotifyMQ();
        lockMarketPayOrderRequestDTO.setOutTradeNo(RandomStringUtils.randomNumeric(12));

        Response<LockMarketPayOrderResponseDTO> lockMarketPayOrderResponseDTOResponse = marketTradeService.lockMarketPayOrder(lockMarketPayOrderRequestDTO);

        log.info("娴嬭瘯缁撴灉 req:{} res:{}", JSON.toJSONString(lockMarketPayOrderRequestDTO), JSON.toJSONString(lockMarketPayOrderResponseDTOResponse));
    }

}
