package com.aigroup.gateway.filter;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.security.InternalIdentityJwt;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayIdentityHeaderSupportTest {

    private static final String SECRET = "unit-test-identity-signing-secret!";

    @Test
    void verifiedIdentityRewritesProtectedHeadersAndMintsJwt() {
        ServerHttpRequest browserRequest = MockServerHttpRequest.get("/api/runs/run-1/events")
                .header("Last-Event-ID", "42")
                .header(CommonConstant.HEADER_USER_ID, "attacker")
                .header(CommonConstant.HEADER_GATEWAY_REQUEST, "false")
                .header(CommonConstant.HEADER_INTERNAL_JWT, "forged.jwt.token")
                .build();

        ServerHttpRequest downstream = GatewayIdentityHeaderSupport.withVerifiedIdentity(
                browserRequest, 1001L, "alice", "USER", "internal-test-token", SECRET);

        assertEquals("42", downstream.getHeaders().getFirst("Last-Event-ID"));
        assertEquals("1001", downstream.getHeaders().getFirst(CommonConstant.HEADER_USER_ID));
        assertEquals("true", downstream.getHeaders().getFirst(CommonConstant.HEADER_GATEWAY_REQUEST));
        assertEquals("internal-test-token", downstream.getHeaders().getFirst(CommonConstant.HEADER_INTERNAL_TOKEN));
        String jwt = downstream.getHeaders().getFirst(CommonConstant.HEADER_INTERNAL_JWT);
        assertNotNull(jwt);
        InternalIdentityJwt.Claims claims = InternalIdentityJwt.verify(SECRET, jwt);
        assertNotNull(claims);
        assertEquals(1001L, claims.userId());
        assertEquals("alice", claims.username());
        assertEquals("USER", claims.role());
    }

    @Test
    void whitelistStripsForgedInternalJwt() {
        ServerHttpRequest browserRequest = MockServerHttpRequest.post("/api/auth/login")
                .header(CommonConstant.HEADER_INTERNAL_JWT, "forged.jwt.token")
                .header(CommonConstant.HEADER_USER_ID, "999")
                .build();

        ServerHttpRequest downstream = GatewayIdentityHeaderSupport.stripUntrustedIdentity(browserRequest);

        assertNull(downstream.getHeaders().getFirst(CommonConstant.HEADER_INTERNAL_JWT));
        assertNull(downstream.getHeaders().getFirst(CommonConstant.HEADER_USER_ID));
    }
}
