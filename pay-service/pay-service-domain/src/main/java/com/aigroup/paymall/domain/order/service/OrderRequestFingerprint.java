package com.aigroup.paymall.domain.order.service;

import com.aigroup.paymall.domain.order.model.entity.ShopCartEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 支付下单幂等指纹。所有字段必须先由服务端完成规范化，商品编码必须使用服务端目录真相。
 */
public final class OrderRequestFingerprint {

    private static final String VERSION = "pay-order-v1";

    private OrderRequestFingerprint() {
    }

    public static String calculate(ShopCartEntity cart) {
        StringBuilder canonical = new StringBuilder(VERSION);
        append(canonical, "user", cart.getUserId());
        append(canonical, "product", cart.getProductId());
        append(canonical, "productCode", cart.getProductCode());
        append(canonical, "marketType", cart.getMarketTypeVO() == null ? null : String.valueOf(cart.getMarketTypeVO().getCode()));
        append(canonical, "activity", cart.getActivityId() == null ? null : String.valueOf(cart.getActivityId()));
        append(canonical, "team", cart.getTeamId());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void append(StringBuilder target, String name, String value) {
        target.append('\n').append(name).append('=');
        if (value == null) {
            target.append("-1:");
            return;
        }
        target.append(value.length()).append(':').append(value);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }
}
