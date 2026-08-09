package com.aigroup.bff.config;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.config.InternalTokenProperties;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeignAuthForwardConfigTest {

    private final RequestInterceptor interceptor = new FeignAuthForwardConfig().authForwardInterceptor(internalTokenProperties());

    @AfterEach
    void clearContext() {
        RequestUserContext.clear();
    }

    @Test
    void userScopedCallForwardsGatewayVerifiedContext() {
        bindUser();
        RequestTemplate template = new RequestTemplate().uri("/api/pay/orders/page");

        interceptor.apply(template);

        assertEquals("42", template.headers().get(CommonConstant.HEADER_USER_ID)
                .iterator().next());
        assertEquals("true", template.headers().get(CommonConstant.HEADER_GATEWAY_REQUEST).iterator().next());
        assertEquals("internal-token", template.headers().get(CommonConstant.HEADER_INTERNAL_TOKEN).iterator().next());
        assertEquals("tester", template.headers().get(CommonConstant.HEADER_USERNAME).iterator().next());
        assertEquals("USER", template.headers().get(CommonConstant.HEADER_ROLE).iterator().next());
    }

    @Test
    void internalCallUsesInternalToken() {
        RequestTemplate template = new RequestTemplate().uri("/internal/benefits/orders/order-1/status");

        interceptor.apply(template);

        assertEquals("internal-token", template.headers().get(CommonConstant.HEADER_INTERNAL_TOKEN)
                .iterator().next());
        assertNull(template.headers().get(CommonConstant.HEADER_USER_ID));
    }

    @Test
    void anonymousCallDoesNotForgeUserContext() {
        RequestTemplate template = new RequestTemplate().uri("/api/member/summary");
        interceptor.apply(template);
        assertNull(template.headers().get(CommonConstant.HEADER_USER_ID));
    }

    private void bindUser() {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader(CommonConstant.HEADER_USER_ID, "42");
        request.addHeader(CommonConstant.HEADER_USERNAME, "tester");
        request.addHeader(CommonConstant.HEADER_ROLE, "USER");
        RequestUserContext.bind(request);
    }

    private InternalTokenProperties internalTokenProperties() {
        InternalTokenProperties properties = new InternalTokenProperties();
        properties.setToken("internal-token");
        return properties;
    }
}
