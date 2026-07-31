package com.linrun.agent.test.domain;

import com.aigroup.common.constant.CommonConstant;
import com.linrun.agent.trigger.http.auth.GatewayUserContextFilter;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

/** Gateway 用户上下文过滤器测试。 */
public class GatewayUserContextFilterTest {

    @Test
    public void shouldBindOwnerIdFromGatewayHeaders() throws Exception {
        GatewayUserContextFilter filter = new GatewayUserContextFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/web/api/v1/gpt/queryAgentStreamIncr");
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, "secret-token");
        request.addHeader(CommonConstant.HEADER_USER_ID, "1001");
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> ownerSeenInChain.set(OwnerRequestContext.currentOwnerId()));

        Assert.assertEquals(Long.valueOf(1001L), ownerSeenInChain.get());
        Assert.assertNull(OwnerRequestContext.currentOwnerId());
    }

    @Test
    public void shouldIgnoreSpoofedUserIdWithoutGatewayProof() throws Exception {
        assertOwnerNotBound(null);
    }

    @Test
    public void shouldIgnoreWrongInternalToken() throws Exception {
        assertOwnerNotBound("wrong-token");
    }

    private void assertOwnerNotBound(String internalToken) throws Exception {
        GatewayUserContextFilter filter = new GatewayUserContextFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/web/api/v1/gpt/queryAgentStreamIncr");
        request.addHeader(CommonConstant.HEADER_USER_ID, "1001");
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        if (internalToken != null) {
            request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, internalToken);
        }
        AtomicReference<Long> ownerSeenInChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> ownerSeenInChain.set(OwnerRequestContext.currentOwnerId()));

        Assert.assertNull(ownerSeenInChain.get());
        Assert.assertNull(OwnerRequestContext.currentOwnerId());
    }
}
