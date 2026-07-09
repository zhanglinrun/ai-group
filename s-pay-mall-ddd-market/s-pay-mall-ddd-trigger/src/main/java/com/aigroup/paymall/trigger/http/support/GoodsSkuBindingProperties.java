package com.aigroup.paymall.trigger.http.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "ai-group.pay")
public class GoodsSkuBindingProperties {

    /**
     * goodsId/productId -> allowed member SKU codes.
     *
     * Example:
     *   ai-group.pay.goods-sku-bindings.9890001[0]=PRO_MONTH
     *   ai-group.pay.goods-sku-bindings.9890001[1]=PRO_YEAR
     */
    private Map<String, List<String>> goodsSkuBindings = Collections.emptyMap();

    public boolean isSkuAllowed(String goodsId, String skuCode) {
        if (goodsId == null || goodsId.isBlank() || skuCode == null || skuCode.isBlank()) {
            return false;
        }
        List<String> allowed = goodsSkuBindings.get(goodsId);
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        return allowed.contains(skuCode);
    }
}

