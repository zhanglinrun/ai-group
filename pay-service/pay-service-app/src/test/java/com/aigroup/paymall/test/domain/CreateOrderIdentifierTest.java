package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.domain.order.model.entity.ShopCartEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CreateOrderIdentifierTest {

    @Test
    public void orderIdentifiersAreCompactUuidValues() {
        ProductEntity product = ProductEntity.builder()
                .productId("sku-1")
                .productCode("quota-1")
                .productName("quota")
                .price(new BigDecimal("1.00"))
                .baseQuota(60L)
                .build();
        ShopCartEntity cart = ShopCartEntity.builder()
                .requestId("request-1")
                .userId("user-1")
                .marketTypeVO(MarketTypeVO.NO_MARKET)
                .build();
        Set<String> identifiers = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String identifier = CreateOrderAggregate
                    .buildOrderEntity(product, cart, "fingerprint", "owner")
                    .getOrderId();
            assertEquals(32, identifier.length());
            assertTrue(identifier.matches("[0-9a-f]{32}"));
            identifiers.add(identifier);
        }
        assertEquals(1_000, identifiers.size());
    }
}
