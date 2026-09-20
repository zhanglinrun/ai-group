package com.aigroup.paymall.domain.order.model.entity;

/** Immutable group lock identity carried by a formed-team event. */
public record TeamSettlementMember(String userId, String source, String channel, String outTradeNo) {
}
