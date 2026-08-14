package com.aigroup.common.filter;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.security.InternalIdentityJwt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayUserContextFilterTest {

    private static final String SECRET = "unit-test-identity-signing-secret!";
    private static final String INTERNAL_TOKEN = "internal-token";

    @AfterEach
    void clearContext() {
        RequestUserContext.clear();
    }

    @Test
    void bindsClaimsFromVerifiedJwtNotSpoofedUserHeader() throws Exception {
        GatewayUserContextFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/member/summary");
        request.addHeader(CommonConstant.HEADER_USER_ID, "999");
        request.addHeader(CommonConstant.HEADER_USERNAME, "attacker");
        request.addHeader(CommonConstant.HEADER_ROLE, "ADMIN");
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, INTERNAL_TOKEN);
        request.addHeader(CommonConstant.HEADER_INTERNAL_JWT,
                InternalIdentityJwt.mint(SECRET, "42", "tester", "USER"));
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();
        AtomicReference<String> roleSeenInChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> {
                    ownerSeenInChain.set(RequestUserContext.getUserId());
                    roleSeenInChain.set(RequestUserContext.getRole());
                });

        assertEquals(42L, ownerSeenInChain.get());
        assertEquals("USER", roleSeenInChain.get());
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

    @Test
    void doesNotBindWhenJwtIsMissing() throws Exception {
        GatewayUserContextFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/member/summary");
        request.addHeader(CommonConstant.HEADER_USER_ID, "42");
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, INTERNAL_TOKEN);
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> ownerSeenInChain.set(RequestUserContext.getUserId()));

        assertNull(ownerSeenInChain.get());
    }

    private GatewayUserContextFilter filter() {
        InternalTokenProperties properties = new InternalTokenProperties();
        properties.setToken(INTERNAL_TOKEN);
        return new GatewayUserContextFilter(properties, SECRET);
    }
}
