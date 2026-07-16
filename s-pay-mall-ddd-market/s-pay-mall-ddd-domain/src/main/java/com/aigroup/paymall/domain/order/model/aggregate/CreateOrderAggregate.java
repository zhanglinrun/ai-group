package com.aigroup.paymall.domain.order.model.aggregate;

import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreateOrderAggregate {

    private String userId;

    private ProductEntity productEntity;

    private OrderEntity orderEntity;

    public static OrderEntity buildOrderEntity(ProductEntity product, Integer marketType){
        return OrderEntity.builder()
                .productId(product.getProductId())
                .productCode(product.getProductCode())
                .productName(product.getProductName())
                .baseQuotaSnapshot(product.getBaseQuota())
                .orderId(RandomStringUtils.randomNumeric(12))
                .orderTime(new Date())
                .orderStatusVO(OrderStatusVO.CREATE)
                .marketType(marketType)
                .build();
    }

}
