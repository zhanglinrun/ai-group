package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.trigger.http.support.GatewayUserResolver;
import com.aigroup.paymall.trigger.http.support.InternalCallbackAuthSupport;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

public class PaySecuritySupportTest {

    @Test
    public void resolveUserId_rejectsDirectAccessWithoutGatewayHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        GatewayUserResolver resolver = new GatewayUserResolver();
        ReflectionTestUtils.setField(resolver, "internalToken", "secret-token");
        try {
            resolver.resolveUserId(request, "10001");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("gateway"));
        }
    }

    @Test
    public void resolveUserId_rejectsMismatchedBodyUserId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Gateway-Request", "true");
        request.addHeader("X-User-Id", "10001");
        request.addHeader("X-Internal-Token", "secret-token");
        GatewayUserResolver resolver = new GatewayUserResolver();
        ReflectionTestUtils.setField(resolver, "internalToken", "secret-token");
        try {
            resolver.resolveUserId(request, "10002");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("mismatch"));
        }
    }

    @Test
    public void resolveUserId_acceptsGatewayIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Gateway-Request", "true");
        request.addHeader("X-User-Id", "10001");
        request.addHeader("X-Internal-Token", "secret-token");
        GatewayUserResolver resolver = new GatewayUserResolver();
        ReflectionTestUtils.setField(resolver, "internalToken", "secret-token");
        Assert.assertEquals("10001", resolver.resolveUserId(request, "10001"));
    }

    @Test
    public void internalCallbackAuth_rejectsMissingToken() {
        InternalCallbackAuthSupport support = new InternalCallbackAuthSupport();
        ReflectionTestUtils.setField(support, "internalToken", "secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        Assert.assertFalse(support.isAuthorized(request));
    }

    @Test
    public void internalCallbackAuth_acceptsValidToken() {
        InternalCallbackAuthSupport support = new InternalCallbackAuthSupport();
        ReflectionTestUtils.setField(support, "internalToken", "secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Token", "secret-token");
        Assert.assertTrue(support.isAuthorized(request));
    }
}
