package com.aigroup.gateway.filter;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.security.InternalIdentityJwt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

/** Builds downstream requests after stripping browser-supplied identity headers. */
final class GatewayIdentityHeaderSupport {

    private GatewayIdentityHeaderSupport() {
    }

    static ServerHttpRequest stripUntrustedIdentity(ServerHttpRequest request) {
        return request.mutate().headers(GatewayIdentityHeaderSupport::stripIdentityHeaders).build();
    }

    static ServerHttpRequest withVerifiedIdentity(ServerHttpRequest request,
                                                  Long userId,
                                                  String username,
                                                  String role,
                                                  String internalToken,
                                                  String signingSecret) {
        String normalizedRole = role == null || role.isBlank() ? "USER" : role;
        String jwt = InternalIdentityJwt.mint(
                signingSecret, String.valueOf(userId), username, normalizedRole);
        return request.mutate()
                .headers(GatewayIdentityHeaderSupport::stripIdentityHeaders)
                .header(CommonConstant.HEADER_USER_ID, String.valueOf(userId))
                .header(CommonConstant.HEADER_GATEWAY_REQUEST, "true")
                .header(CommonConstant.HEADER_INTERNAL_TOKEN, internalToken)
                .headers(headers -> {
                    if (jwt != null && !jwt.isBlank()) {
                        headers.set(CommonConstant.HEADER_INTERNAL_JWT, jwt);
                    }
                    if (username != null) {
                        headers.set(CommonConstant.HEADER_USERNAME, username);
                    }
                    if (role != null) {
                        headers.set(CommonConstant.HEADER_ROLE, role);
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

    private static void stripIdentityHeaders(HttpHeaders headers) {
        headers.remove(CommonConstant.HEADER_USER_ID);
        headers.remove(CommonConstant.HEADER_USERNAME);
        headers.remove(CommonConstant.HEADER_ROLE);
        headers.remove(CommonConstant.HEADER_GATEWAY_REQUEST);
        headers.remove(CommonConstant.HEADER_INTERNAL_TOKEN);
        headers.remove(CommonConstant.HEADER_INTERNAL_JWT);
    }
}
