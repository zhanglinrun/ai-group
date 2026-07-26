package com.aigroup.paymall.infrastructure.gateway;

import com.aigroup.paymall.infrastructure.gateway.dto.ProductDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.MemberSkuDTO;
import com.aigroup.paymall.infrastructure.gateway.response.MemberResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ProductRPC {

    private final PayProductCatalogProperties payProductCatalogProperties;
    private final IMemberCatalogService memberCatalogService;

    public ProductRPC(PayProductCatalogProperties payProductCatalogProperties,
                      IMemberCatalogService memberCatalogService) {
        this.payProductCatalogProperties = payProductCatalogProperties;
        this.memberCatalogService = memberCatalogService;
    }

    /**
     * 按 goodsId 从配置目录解析商品名与展示价。
     * 原实现无视 productId 恒返回「MyBatisBook ¥100」，导致所有套餐下单主题/金额相同；
     * 现优先从 member-service 解析额度包价格和基础额度；仅本地开发可显式启用静态兜底。
     */
    public ProductDTO queryProductByProductId(String productId) {
        try {
            MemberResult<MemberSkuDTO> body = memberCatalogService.queryEnabledSkuByGoodsId(productId);
            if (body == null || !Integer.valueOf(200).equals(body.getCode()) || body.getData() == null) {
                throw new IllegalStateException("member catalog rejected goodsId=" + productId);
            }
            MemberSkuDTO sku = body.getData();
            if (!productId.equals(sku.getGroupGoodsId()) || sku.getCode() == null || sku.getPrice() == null
                    || sku.getBaseQuota() == null || sku.getBaseQuota() <= 0) {
                throw new IllegalStateException("member catalog returned invalid quota SKU for goodsId=" + productId);
            }
            ProductDTO product = new ProductDTO();
            product.setProductId(productId);
            product.setProductName(sku.getName());
            product.setProductDesc(sku.getName());
            product.setPrice(sku.getPrice());
            product.setProductCode(sku.getCode());
            product.setBaseQuota(sku.getBaseQuota());
            return product;
        } catch (Exception e) {
            if (!payProductCatalogProperties.isFallbackEnabled()) {
                throw new IllegalStateException("member catalog unavailable; refusing to snapshot stale quota package", e);
            }
            log.warn("member catalog unavailable, using explicit local fallback goodsId={}", productId, e);
        }

        PayProductCatalogProperties.Item item = payProductCatalogProperties.find(productId);
        if (item == null || item.getName() == null || item.getPrice() == null
                || item.getProductCode() == null || item.getBaseQuota() == null || item.getBaseQuota() <= 0) {
            throw new IllegalStateException("local quota package fallback is not configured: " + productId);
        }
        ProductDTO productVO = new ProductDTO();
        productVO.setProductId(productId);
        productVO.setProductName(item.getName());
        productVO.setProductDesc(item.getName());
        productVO.setPrice(item.getPrice());
        productVO.setProductCode(item.getProductCode());
        productVO.setBaseQuota(item.getBaseQuota());
        return productVO;
    }

}
