package com.aigroup.groupbuy.domain.activity.service.trial.node;

import com.aigroup.groupbuy.domain.activity.model.entity.MarketProductEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.SCSkuActivityVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.SkuVO;
import com.aigroup.groupbuy.domain.activity.service.trial.factory.DefaultActivityStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 绾跨▼妗堜緥涓句緥
 * @create 2025-04-03 07:44
 */
@Slf4j
//@Service
public class MarketNode2CompletableFuture extends MarketNode {

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Override
    protected void multiThread(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // 寮傛鏌ヨ娲诲姩閰嶇疆
        CompletableFuture<GroupBuyActivityDiscountVO> groupBuyActivityDiscountVOCompletableFuture = CompletableFuture.supplyAsync(() -> {
            try {
                Long availableActivityId = requestParameter.getActivityId();
                if (null == requestParameter.getActivityId()) {
                    // 鏌ヨ娓犻亾鍟嗗搧娲诲姩閰嶇疆鍏宠仈閰嶇疆
                    SCSkuActivityVO scSkuActivityVO = repository.querySCSkuActivityBySCGoodsId(requestParameter.getSource(), requestParameter.getChannel(), requestParameter.getGoodsId());
                    if (null == scSkuActivityVO) return null;
                    availableActivityId = scSkuActivityVO.getActivityId();
                }
                // 鏌ヨ娲诲姩閰嶇疆
                return repository.queryGroupBuyActivityDiscountVO(availableActivityId);
            } catch (Exception e) {
                log.error("寮傛鏌ヨ娲诲姩閰嶇疆寮傚父", e);
                return null;
            }
        }, threadPoolExecutor);

        // 寮傛鏌ヨ鍟嗗搧淇℃伅 - 鍦ㄥ疄闄呯敓浜т腑锛屽晢鍝佹湁鍚屾搴撴垨鑰呰皟鐢ㄦ帴鍙ｆ煡璇€?傝繖閲屾殏鏃朵娇鐢―B鏂瑰紡鏌ヨ銆?
        CompletableFuture<SkuVO> skuVOCompletableFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return repository.querySkuByGoodsId(requestParameter.getGoodsId());
            } catch (Exception e) {
                log.error("寮傛鏌ヨ鍟嗗搧淇℃伅寮傚父", e);
                return null;
            }
        }, threadPoolExecutor);

        // 绛夊緟鎵?鏈夊紓姝ヤ换鍔″畬鎴愬苟鍐欏叆涓婁笅鏂?
        CompletableFuture.allOf(groupBuyActivityDiscountVOCompletableFuture, skuVOCompletableFuture)
                .thenRun(() -> {
                    dynamicContext.setGroupBuyActivityDiscountVO(groupBuyActivityDiscountVOCompletableFuture.join());
                    dynamicContext.setSkuVO(skuVOCompletableFuture.join());
                }).join();

        log.info("MarketNode2 async load done userId={}", requestParameter.getUserId());
    }
}
