package com.aigroup.common.filter;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.config.InternalTokenProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayUserContextFilterTest {

    @AfterEach
    void clearContext() {
        RequestUserContext.clear();
    }

    @Test
    void bindsOnlyGatewayMarkedRequestWithInternalToken() throws Exception {
        GatewayUserContextFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/member/summary");
        request.addHeader(CommonConstant.HEADER_USER_ID, "42");
        request.addHeader(CommonConstant.HEADER_USERNAME, "tester");
        request.addHeader(CommonConstant.HEADER_ROLE, "USER");
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, "internal-token");
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> {
                    ownerSeenInChain.set(RequestUserContext.getUserId());
                });

        assertEquals(42L, ownerSeenInChain.get());
        assertNull(RequestUserContext.getUserId());
    }

    @Test
    void ignoresIdentityHeadersWithoutGatewayProof() throws Exception {
        GatewayUserContextFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/member/summary");
        request.addHeader(CommonConstant.HEADER_USER_ID, "999");
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> ownerSeenInChain.set(RequestUserContext.getUserId()));

        assertNull(ownerSeenInChain.get());
        assertNull(RequestUserContext.getUserId());
    }

    private GatewayUserContextFilter filter() {
        InternalTokenProperties properties = new InternalTokenProperties();
        properties.setToken("internal-token");
        return new GatewayUserContextFilter(properties);
    }
}
