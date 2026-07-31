package com.linrun.agent.test.domain;

import com.aigroup.common.constant.CommonConstant;
import com.linrun.agent.trigger.http.auth.InternalApiTokenFilter;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

/** Internal management endpoints must remain closed without the internal token. */
public class InternalApiTokenFilterTest {

    @Test
    public void shouldRejectWhenInternalTokenIsNotConfigured() throws Exception {
        FilterInvocation invocation = invoke(new InternalApiTokenFilter(""), request());

        Assert.assertEquals(403, invocation.response.getStatus());
        Assert.assertFalse(invocation.chainCalled.get());
    }

    @Test
    public void shouldAllowValidInternalServiceCall() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, "internal-token");

        FilterInvocation invocation = invoke(new InternalApiTokenFilter("internal-token"), request);

        Assert.assertTrue(invocation.chainCalled.get());
        Assert.assertEquals("internal-token", request.getAttribute(InternalApiTokenFilter.ACTOR_TYPE_ATTRIBUTE));
    }

    @Test
    public void shouldRejectGatewayRequestWithoutAdminRole() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, "internal-token");
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        request.addHeader(CommonConstant.HEADER_ROLE, "USER");

        FilterInvocation invocation = invoke(new InternalApiTokenFilter("internal-token"), request);

        Assert.assertEquals(403, invocation.response.getStatus());
        Assert.assertFalse(invocation.chainCalled.get());
    }

    @Test
    public void shouldAllowGatewayRequestWithAdminRole() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, "internal-token");
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        request.addHeader(CommonConstant.HEADER_ROLE, "ADMIN");
        request.addHeader(CommonConstant.HEADER_USER_ID, "1001");

        FilterInvocation invocation = invoke(new InternalApiTokenFilter("internal-token"), request);

        Assert.assertTrue(invocation.chainCalled.get());
        Assert.assertEquals("internal-token", request.getAttribute(InternalApiTokenFilter.ACTOR_TYPE_ATTRIBUTE));
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/admin/users");
    }

    private FilterInvocation invoke(InternalApiTokenFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));
        return new FilterInvocation(response, chainCalled);
    }

    private record FilterInvocation(MockHttpServletResponse response, AtomicBoolean chainCalled) {
    }
}
