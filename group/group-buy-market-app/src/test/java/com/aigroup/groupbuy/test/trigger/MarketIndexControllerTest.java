package com.aigroup.groupbuy.test.trigger;

import com.aigroup.groupbuy.api.dto.GoodsMarketRequestDTO;
import com.aigroup.groupbuy.api.dto.GoodsMarketResponseDTO;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.trigger.http.MarketIndexController;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 钀ラ攢棣栭〉鏈嶅姟
 * @create 2025-02-02 16:05
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class MarketIndexControllerTest {

    @Resource
    private MarketIndexController marketIndexController;

    @Test
    public void test_queryGroupBuyMarketConfig() {
        GoodsMarketRequestDTO requestDTO = new GoodsMarketRequestDTO();
        requestDTO.setSource("s01");
        requestDTO.setChannel("c01");
        requestDTO.setUserId("xfg01");
        requestDTO.setGoodsId("9890001");

        Response<GoodsMarketResponseDTO> response = marketIndexController.queryGroupBuyMarketConfig(requestDTO);

        log.info("璇锋眰鍙傛暟:{}", JSON.toJSONString(requestDTO));
        log.info("搴旂瓟缁撴灉:{}", JSON.toJSONString(response));
    }

}
