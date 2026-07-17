package com.linrun.agent.test.domain;

import jakarta.servlet.FilterChain;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.linrun.agent.trigger.http.auth.GatewayUserContextFilter;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway 用户上下文过滤器测试。
 */
public class GatewayUserContextFilterTest {

    @Test
    public void shouldBindOwnerIdFromGatewayHeader() throws Exception {
        GatewayUserContextFilter filter = new GatewayUserContextFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/web/api/v1/gpt/queryAgentStreamIncr");
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
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/web/api/v1/gpt/queryAgentStreamIncr");
        request.addHeader("X-User-Id", "1001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> ownerSeenInChain.set(OwnerRequestContext.currentOwnerId()));

        Assert.assertNull(ownerSeenInChain.get());
    }

    @Test
    public void shouldIgnoreGatewayIdentityWhenInternalTokenIsMissing() throws Exception {
        assertOwnerNotBound("true", "1001", null);
    }

    @Test
    public void shouldIgnoreGatewayIdentityWhenInternalTokenDoesNotMatch() throws Exception {
        assertOwnerNotBound("true", "1001", "wrong-token");
    }

    @Test
    public void shouldIgnoreMalformedGatewayUserId() throws Exception {
        assertOwnerNotBound("true", "not-a-number", "secret-token");
    }

    private void assertOwnerNotBound(String gatewayMarker,
                                     String userId,
                                     String internalToken) throws Exception {
        GatewayUserContextFilter filter = new GatewayUserContextFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/web/api/v1/gpt/queryAgentStreamIncr");
        if (gatewayMarker != null) {
            request.addHeader("X-Gateway-Request", gatewayMarker);
        }
        if (userId != null) {
            request.addHeader("X-User-Id", userId);
        }
        if (internalToken != null) {
            request.addHeader("X-Internal-Token", internalToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> ownerSeenInChain.set(OwnerRequestContext.currentOwnerId()));

        Assert.assertNull(ownerSeenInChain.get());
        Assert.assertNull(OwnerRequestContext.currentOwnerId());
    }
}
