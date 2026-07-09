package com.aigroup.paymall.infrastructure.gateway;

import com.aigroup.paymall.infrastructure.gateway.dto.ProductDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class ProductRPC {

    /** 目录未命中时的兜底商品（legacy 演示商品 9890001 的历史行为） */
    private static final String FALLBACK_NAME = "MyBatisBook";
    private static final BigDecimal FALLBACK_PRICE = new BigDecimal("100.00");

    @Resource
    private PayProductCatalogProperties payProductCatalogProperties;

    /**
     * 按 goodsId 从配置目录解析商品名与展示价。
     * 原实现无视 productId 恒返回「MyBatisBook ¥100」，导致所有套餐下单主题/金额相同；
     * 现按目录解析（月卡/年卡/加油包各自价格），未配置的商品退回原兜底值。
     */
    public ProductDTO queryProductByProductId(String productId) {
        PayProductCatalogProperties.Item item = payProductCatalogProperties.find(productId);
        ProductDTO productVO = new ProductDTO();
        productVO.setProductId(productId);
        if (item != null && item.getName() != null && item.getPrice() != null) {
            productVO.setProductName(item.getName());
            productVO.setProductDesc(item.getName());
            productVO.setPrice(item.getPrice());
        } else {
            productVO.setProductName(FALLBACK_NAME);
            productVO.setProductDesc(FALLBACK_NAME);
            productVO.setPrice(FALLBACK_PRICE);
        }
        return productVO;
    }

}
