package com.aigroup.gateway.filter;

import com.aigroup.common.constant.CommonConstant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayIdentityHeaderSupportTest {

    @Test
    void verifiedIdentityRewritesOnlyProtectedHeadersAndPreservesSseCursor() {
        ServerHttpRequest browserRequest = MockServerHttpRequest.get("/api/agent/runs/run-1/events")
                .header("Last-Event-ID", "42")
                .header(CommonConstant.HEADER_USER_ID, "attacker")
                .header(CommonConstant.HEADER_GATEWAY_REQUEST, "false")
                .header("X-Service-Identity", "forged")
                .build();

        ServerHttpRequest downstream = GatewayIdentityHeaderSupport.withVerifiedIdentity(
                browserRequest, 1001L, "alice", "USER", "internal-test-token");

        assertEquals("42", downstream.getHeaders().getFirst("Last-Event-ID"));
        assertEquals("1001", downstream.getHeaders().getFirst(CommonConstant.HEADER_USER_ID));
        assertEquals("true", downstream.getHeaders().getFirst(CommonConstant.HEADER_GATEWAY_REQUEST));
        assertEquals("internal-test-token", downstream.getHeaders().getFirst(CommonConstant.HEADER_INTERNAL_TOKEN));
        assertNull(downstream.getHeaders().getFirst("X-Service-Identity"));
    }
}
