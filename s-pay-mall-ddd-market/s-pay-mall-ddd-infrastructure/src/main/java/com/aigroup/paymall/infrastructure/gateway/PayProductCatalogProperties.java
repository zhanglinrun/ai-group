package com.aigroup.paymall.infrastructure.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

/**
 * 支付侧商品目录（goodsId -> 名称/展示价）。
 *
 * <p>替代原 ProductRPC 写死的「MyBatisBook ¥100」桩：不同套餐（月卡/年卡/加油包）
 * 各自有独立商品与价格，下单主题（subject）与展示金额（total_amount）按目录解析。
 * 与 member_db.product_sku、group_buy_market.sku 的 seed 保持一致。</p>
 *
 * 配置示例（application-dev.yml）：
 * <pre>
 * ai-group:
 *   pay:
 *     catalog:
 *       products:
 *         "9890002": { name: "Pro 月卡", price: 49.00 }
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-group.pay.catalog")
public class PayProductCatalogProperties {

    private Map<String, Item> products = Collections.emptyMap();

    public Item find(String goodsId) {
        if (goodsId == null || products == null) {
            return null;
        }
        return products.get(goodsId);
    }

    @Data
    public static class Item {
        private String name;
        private BigDecimal price;
    }
}
