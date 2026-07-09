package com.aigroup.groupbuy.domain.activity.service.trial.node;

import com.aigroup.groupbuy.domain.activity.model.entity.MarketProductEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.SCSkuActivityVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.SkuVO;
import com.aigroup.groupbuy.domain.activity.service.discount.IDiscountCalculateService;
import com.aigroup.groupbuy.domain.activity.service.trial.AbstractGroupBuyMarketSupport;
import com.aigroup.groupbuy.domain.activity.service.trial.factory.DefaultActivityStrategyFactory;
import com.aigroup.groupbuy.domain.activity.service.trial.thread.QueryGroupBuyActivityDiscountVOThreadTask;
import com.aigroup.groupbuy.domain.activity.service.trial.thread.QuerySkuVOFromDBThreadTask;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 钀ラ攢浼樻儬鑺傜偣
 * @create 2024-12-14 14:30
 */
@Slf4j
@Service
public class MarketNode extends AbstractGroupBuyMarketSupport<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> {

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;
    /**
     * <a href="https://bugstack.cn/md/road-map/spring-dependency-injection.html">Spring 娉ㄥ叆璇︾粏璇存槑</a>
     */
    @Resource
    private Map<String, IDiscountCalculateService> discountCalculateServiceMap;
    @Resource
    private ErrorNode errorNode;
    @Resource
    private TagNode tagNode;

    /**
     * 鍦?MarketNode2CompletableFuture 缁ф壙鐨勫瓙绫诲疄鐜颁竴涓?CompletableFuture 澶氱嚎绋嬫柟寮忋??
     * <p>
     * 1. CompletableFuture锛氶?傜敤浜庡ぇ澶氭暟鐜颁唬 Java 搴旂敤锛屽挨鍏跺湪闇?瑕佺伒娲讳换鍔＄紪鎺掓椂銆?
     * 2.  FutureTask锛氫换鍔℃瀬搴︾畝鍗曪紝閫傚悎绠?鍗曞満鏅??
     * <p>
     * | 瀵规瘮缁村害    | FutureTask             | CompletableFuture                |
     * | :--------------- | :-------------------------- | :------------------------------------- |
     * | 浠诲姟缂栨帓鑳藉姏 | 寮憋紙闇?鎵嬪姩绠＄悊澶氫釜 Future锛?| 寮猴紙鍐呯疆 `thenApply`銆乣allOf` 绛夋柟娉曪級 |
     * | 浠ｇ爜绠?娲佹?? | 鍐椾綑锛堟樉寮忚皟鐢?`get()`锛?   | 绠?娲侊紙閾惧紡璋冪敤锛岄?昏緫鍐呰仛锛?            |
     * | 寮傚父澶勭悊   | 绻佺悙锛堥渶鎹曡幏澶氫釜寮傚父锛?     | 浼橀泤锛堟敮鎸?`exceptionally` 缁熶竴澶勭悊锛? |
     * | 绾跨▼闃诲     | 鍙兘澶氭闃诲涓荤嚎绋?         | 闈為樆濉炴垨鍗曟闃诲锛堝 `join()`锛?       |
     * | 閫傜敤鍦烘櫙     | 绠?鍗曚换鍔°?佷綆鐗堟湰 Java 鐜  | 澶嶆潅寮傛娴佺▼銆丣ava 8+ 鐜             |
     * <p>
     * 浣跨敤锛汳arketNode 鐨?@Service 娉ㄩ噴鎺夛紝MarketNode2CompletableFuture 鐨?@Service 鎵撳紑锛屽氨鍙互浣跨敤浜嗐??
     */
    @Override
    protected void multiThread(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // 寮傛鏌ヨ娲诲姩閰嶇疆
        QueryGroupBuyActivityDiscountVOThreadTask queryGroupBuyActivityDiscountVOThreadTask = new QueryGroupBuyActivityDiscountVOThreadTask(requestParameter.getActivityId(), requestParameter.getSource(), requestParameter.getChannel(), requestParameter.getGoodsId(), repository);
        FutureTask<GroupBuyActivityDiscountVO> groupBuyActivityDiscountVOFutureTask = new FutureTask<>(queryGroupBuyActivityDiscountVOThreadTask);
        threadPoolExecutor.execute(groupBuyActivityDiscountVOFutureTask);

        // 寮傛鏌ヨ鍟嗗搧淇℃伅 - 鍦ㄥ疄闄呯敓浜т腑锛屽晢鍝佹湁鍚屾搴撴垨鑰呰皟鐢ㄦ帴鍙ｆ煡璇€?傝繖閲屾殏鏃朵娇鐢―B鏂瑰紡鏌ヨ銆?
        QuerySkuVOFromDBThreadTask querySkuVOFromDBThreadTask = new QuerySkuVOFromDBThreadTask(requestParameter.getGoodsId(), repository);
        FutureTask<SkuVO> skuVOFutureTask = new FutureTask<>(querySkuVOFromDBThreadTask);
        threadPoolExecutor.execute(skuVOFutureTask);

        // 鍐欏叆涓婁笅鏂?- 瀵逛簬涓?浜涘鏉傚満鏅紝鑾峰彇鏁版嵁鐨勬搷浣滐紝鏈夋椂鍊欎細鍦ㄤ笅N涓妭鐐硅幏鍙栵紝杩欐牱鍓嶇疆鏌ヨ鏁版嵁锛屽彲浠ユ彁楂樻帴鍙ｅ搷搴旀晥鐜?
        dynamicContext.setGroupBuyActivityDiscountVO(groupBuyActivityDiscountVOFutureTask.get(timeout, TimeUnit.MILLISECONDS));
        dynamicContext.setSkuVO(skuVOFutureTask.get(timeout, TimeUnit.MILLISECONDS));

        log.info("MarketNode async load done userId={}", requestParameter.getUserId());
    }

    @Override
    public TrialBalanceEntity doApply(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("鎷煎洟鍟嗗搧鏌ヨ璇曠畻鏈嶅姟-MarketNode userId:{} requestParameter:{}", requestParameter.getUserId(), JSON.toJSONString(requestParameter));

        // 鑾峰彇涓婁笅鏂囨暟鎹?
        GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = dynamicContext.getGroupBuyActivityDiscountVO();
        if (null == groupBuyActivityDiscountVO) {
            return router(requestParameter, dynamicContext);
        }

        GroupBuyActivityDiscountVO.GroupBuyDiscount groupBuyDiscount = groupBuyActivityDiscountVO.getGroupBuyDiscount();
        SkuVO skuVO = dynamicContext.getSkuVO();
        if (null == groupBuyDiscount || null == skuVO) {
            return router(requestParameter, dynamicContext);
        }

        // 浼樻儬璇曠畻
        IDiscountCalculateService discountCalculateService = discountCalculateServiceMap.get(groupBuyDiscount.getMarketPlan());
        if (null == discountCalculateService) {
            log.info("涓嶅瓨鍦▄}绫诲瀷鐨勬姌鎵ｈ绠楁湇鍔★紝鏀寔绫诲瀷涓?{}", groupBuyDiscount.getMarketPlan(), JSON.toJSONString(discountCalculateServiceMap.keySet()));
            throw new AppException(ResponseCode.E0001.getCode(), ResponseCode.E0001.getInfo());
        }

        // 鎶樻墸浠锋牸
        BigDecimal payPrice = discountCalculateService.calculate(requestParameter.getUserId(), skuVO.getOriginalPrice(), groupBuyDiscount);
        dynamicContext.setDeductionPrice(skuVO.getOriginalPrice().subtract(payPrice));
        dynamicContext.setPayPrice(payPrice);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> get(MarketProductEntity requestParameter, DefaultActivityStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 涓嶅瓨鍦ㄩ厤缃殑鎷煎洟娲诲姩锛岃蛋寮傚父鑺傜偣
        if (null == dynamicContext.getGroupBuyActivityDiscountVO() || null == dynamicContext.getSkuVO() || null == dynamicContext.getDeductionPrice()) {
            return errorNode;
        }

        return tagNode;
    }

}
