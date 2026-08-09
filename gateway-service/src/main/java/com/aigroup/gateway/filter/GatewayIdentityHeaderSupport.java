package com.aigroup.gateway.filter;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.security.IdentitySignature;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.time.Instant;
import java.util.UUID;

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
                                                  String internalToken) {
        return withVerifiedIdentity(request, userId, username, role, internalToken, "");
    }

    static ServerHttpRequest withVerifiedIdentity(ServerHttpRequest request,
                                                  Long userId,
                                                  String username,
                                                  String role,
                                                  String internalToken,
                                                  String signingSecret) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String normalizedRole = role == null || role.isBlank() ? "USER" : role;
        String signature = IdentitySignature.sign(
                signingSecret,
                String.valueOf(userId),
                normalizedRole,
                timestamp,
                nonce,
                request.getPath().value());
        return request.mutate()
                .headers(GatewayIdentityHeaderSupport::stripIdentityHeaders)
                .header(CommonConstant.HEADER_USER_ID, String.valueOf(userId))
                .header(CommonConstant.HEADER_GATEWAY_REQUEST, "true")
                .header(CommonConstant.HEADER_INTERNAL_TOKEN, internalToken)
                .header(CommonConstant.HEADER_AUTH_TIMESTAMP, timestamp)
                .header(CommonConstant.HEADER_AUTH_NONCE, nonce)
                .header(CommonConstant.HEADER_AUTH_SIGNATURE, signature)
                .headers(headers -> {
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
        headers.remove("X-Gateway-Identity");
        headers.remove("X-Service-Identity");
    }
}
