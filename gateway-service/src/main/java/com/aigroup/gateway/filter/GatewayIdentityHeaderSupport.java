package com.aigroup.gateway.filter;

import com.aigroup.common.constant.CommonConstant;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;

/**
 * Builds downstream requests with spoofed identity headers stripped and gateway-verified
 * identity injected in a single mutate chain (strip first, then inject).
 */
final class GatewayIdentityHeaderSupport {

    private GatewayIdentityHeaderSupport() {
    }

    static ServerHttpRequest stripUntrustedIdentity(ServerHttpRequest request) {
        return request.mutate()
                .headers(GatewayIdentityHeaderSupport::stripIdentityHeaders)
                .build();
    }

    static ServerHttpRequest withVerifiedIdentity(ServerHttpRequest request, Long userId, String username, String role) {
        return request.mutate()
                .headers(GatewayIdentityHeaderSupport::stripIdentityHeaders)
                .header(CommonConstant.HEADER_USER_ID, String.valueOf(userId))
                .header(CommonConstant.HEADER_GATEWAY_REQUEST, "true")
                .headers(headers -> {
                    if (StringUtils.hasText(username)) {
                        headers.set(CommonConstant.HEADER_USERNAME, username);
                    } else {
                        headers.remove(CommonConstant.HEADER_USERNAME);
                    }
                    if (StringUtils.hasText(role)) {
                        headers.set(CommonConstant.HEADER_ROLE, role);
                    } else {
                        headers.remove(CommonConstant.HEADER_ROLE);
                    }
                })
                .build();
    }

    static ServerHttpRequest withVerifiedIdentity(ServerHttpRequest request, Long userId, String username, String role, String internalToken) {
        return request.mutate()
                .headers(GatewayIdentityHeaderSupport::stripIdentityHeaders)
                .header(CommonConstant.HEADER_USER_ID, String.valueOf(userId))
                .header(CommonConstant.HEADER_GATEWAY_REQUEST, "true")
                .header(CommonConstant.HEADER_INTERNAL_TOKEN, internalToken)
                .headers(headers -> {
                    if (StringUtils.hasText(username)) {
                        headers.set(CommonConstant.HEADER_USERNAME, username);
                    } else {
                        headers.remove(CommonConstant.HEADER_USERNAME);
                    }
                    if (StringUtils.hasText(role)) {
                        headers.set(CommonConstant.HEADER_ROLE, role);
                    } else {
                        headers.remove(CommonConstant.HEADER_ROLE);
                    }
                })
                .build();
    }

    static ServerHttpRequest withInternalToken(ServerHttpRequest request, String internalToken) {
        return request.mutate()
                .headers(GatewayIdentityHeaderSupport::stripIdentityHeaders)
                .header(CommonConstant.HEADER_INTERNAL_TOKEN, internalToken)
                .build();
    }

    private static void stripIdentityHeaders(org.springframework.http.HttpHeaders headers) {
        headers.remove(CommonConstant.HEADER_USER_ID);
        headers.remove(CommonConstant.HEADER_USERNAME);
        headers.remove(CommonConstant.HEADER_ROLE);
        headers.remove(CommonConstant.HEADER_GATEWAY_REQUEST);
        headers.remove(CommonConstant.HEADER_INTERNAL_TOKEN);
    }
}
