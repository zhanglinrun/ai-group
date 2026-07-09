package org.wwz.ai.test.domain;

import jakarta.servlet.FilterChain;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.wwz.ai.trigger.http.auth.GatewayUserContextFilter;
import org.wwz.ai.types.agent.owner.OwnerRequestContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway 用户上下文过滤器测试。
 */
public class GatewayUserContextFilterTest {

    @Test
    public void shouldBindOwnerIdFromGatewayHeader() throws Exception {
        GatewayUserContextFilter filter = new GatewayUserContextFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/AutoAgent");
        request.addHeader("X-Gateway-Request", "true");
        request.addHeader("X-User-Id", "1001");
        request.addHeader("X-Internal-Token", "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> ownerSeenInChain.set(OwnerRequestContext.currentOwnerId()));

        Assert.assertEquals(Long.valueOf(1001L), ownerSeenInChain.get());
        Assert.assertNull(OwnerRequestContext.currentOwnerId());
    }

    @Test
    public void shouldIgnoreSpoofedUserIdWithoutGatewayMarker() throws Exception {
        GatewayUserContextFilter filter = new GatewayUserContextFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/AutoAgent");
        request.addHeader("X-User-Id", "1001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> ownerSeenInChain.set(OwnerRequestContext.currentOwnerId()));

        Assert.assertNull(ownerSeenInChain.get());
    }
}
