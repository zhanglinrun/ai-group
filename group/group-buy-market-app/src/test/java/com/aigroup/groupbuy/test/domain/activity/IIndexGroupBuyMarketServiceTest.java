package com.aigroup.groupbuy.test.domain.activity;

import com.aigroup.groupbuy.domain.activity.model.entity.MarketProductEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.service.IIndexGroupBuyMarketService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 棣栭〉钀ラ攢鏈嶅姟鎺ュ彛娴嬭瘯
 * @create 2024-12-21 11:08
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class IIndexGroupBuyMarketServiceTest {

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;

    /**
     * 娴嬭瘯浜虹兢鏍囩鍔熻兘鐨勬椂鍊欙紝鍙互杩涘叆 ITagServiceTest#test_tag_job 鎵ц浜虹兢鍐欏叆
     */
    @Test
    public void test_indexMarketTrial() throws Exception {
        MarketProductEntity marketProductEntity = new MarketProductEntity();
        marketProductEntity.setUserId("xiaofuge");
        marketProductEntity.setSource("s01");
        marketProductEntity.setChannel("c01");
        marketProductEntity.setGoodsId("9890001");

        TrialBalanceEntity trialBalanceEntity = indexGroupBuyMarketService.indexMarketTrial(marketProductEntity);
        log.info("璇锋眰鍙傛暟:{}", JSON.toJSONString(marketProductEntity));
        log.info("杩斿洖缁撴灉:{}", JSON.toJSONString(trialBalanceEntity));
    }

    @Test
    public void test_indexMarketTrial_no_tag() throws Exception {
        MarketProductEntity marketProductEntity = new MarketProductEntity();
        marketProductEntity.setUserId("dacihua");
        marketProductEntity.setSource("s01");
        marketProductEntity.setChannel("c01");
        marketProductEntity.setGoodsId("9890001");

        TrialBalanceEntity trialBalanceEntity = indexGroupBuyMarketService.indexMarketTrial(marketProductEntity);
        log.info("璇锋眰鍙傛暟:{}", JSON.toJSONString(marketProductEntity));
        log.info("杩斿洖缁撴灉:{}", JSON.toJSONString(trialBalanceEntity));
    }

    @Test
    public void test_indexMarketTrial_error() throws Exception {
        MarketProductEntity marketProductEntity = new MarketProductEntity();
        marketProductEntity.setUserId("xiaofuge");
        marketProductEntity.setSource("s01");
        marketProductEntity.setChannel("c01");
        marketProductEntity.setGoodsId("9890002");

        TrialBalanceEntity trialBalanceEntity = indexGroupBuyMarketService.indexMarketTrial(marketProductEntity);
        log.info("璇锋眰鍙傛暟:{}", JSON.toJSONString(marketProductEntity));
        log.info("杩斿洖缁撴灉:{}", JSON.toJSONString(trialBalanceEntity));
    }

}
