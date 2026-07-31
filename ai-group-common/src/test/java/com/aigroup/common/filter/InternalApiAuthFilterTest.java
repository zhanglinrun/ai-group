package com.aigroup.common.filter;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalApiAuthFilterTest {

    @Test
    void actuatorRequiresTheSameNonblankInternalCredentialAsInternalApis() throws Exception {
        InternalTokenProperties properties = new InternalTokenProperties();
        properties.setToken("internal-token");
        InternalApiAuthFilter filter = new InternalApiAuthFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertEquals(403, response.getStatus());
        assertFalse(chainCalled.get());

        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, "internal-token");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));
        assertTrue(chainCalled.get());
        assertEquals("internal-token", request.getAttribute(InternalApiAuthFilter.AUDIT_ACTOR_TYPE_ATTRIBUTE));
    }

    @Test
    void missingOrWrongTokenIsRejected() throws Exception {
        InternalTokenProperties properties = new InternalTokenProperties();
        properties.setToken("internal-token");
        InternalApiAuthFilter filter = new InternalApiAuthFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/benefits/orders/order-1/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertEquals(403, response.getStatus());
        assertFalse(chainCalled.get());
    }
}
