package com.aigroup.paymall.domain.order.model.valobj;

/** 支付订单创建的 durable 副作用边界。 */
public enum OrderCreateStage {
    LOCAL_CREATED,
    GROUP_LOCKED,
    PROVIDER_STARTED,
    PREPAY_READY,
    MANUAL_REVIEW
}
