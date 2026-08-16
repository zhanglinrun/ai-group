package com.aigroup.paymall.test.trigger;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.security.InternalIdentityJwt;
import com.aigroup.paymall.trigger.http.support.GatewayUserResolver;
import com.aigroup.paymall.trigger.http.support.InternalCallbackAuthSupport;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

public class PaySecuritySupportTest {

    private static final String SECRET = "unit-test-identity-signing-secret!";
    private static final String INTERNAL_TOKEN = "secret-token";

    @Test
    public void resolveUserId_rejectsDirectAccessWithoutGatewayHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        GatewayUserResolver resolver = resolver();
        try {
            resolver.resolveUserId(request, "10001");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("gateway"));
        }
    }

    @Test
    public void resolveUserId_rejectsMismatchedBodyUserId() {
        MockHttpServletRequest request = gatewayRequest("10001");
        try {
            resolver().resolveUserId(request, "10002");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("mismatch"));
        }
    }

    @Test
    public void resolveUserId_acceptsJwtSubjectNotSpoofedUserHeader() {
        MockHttpServletRequest request = gatewayRequest("10001");
        request.addHeader(CommonConstant.HEADER_USER_ID, "999");
        Assert.assertEquals("10001", resolver().resolveUserId(request, "10001"));
    }

    @Test
    public void resolveUserId_rejectsMissingJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, INTERNAL_TOKEN);
        request.addHeader(CommonConstant.HEADER_USER_ID, "10001");
        try {
            resolver().resolveUserId(request, "10001");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("missing authenticated user"));
        }
    }

    @Test
    public void internalCallbackAuth_rejectsMissingToken() {
        InternalCallbackAuthSupport support = new InternalCallbackAuthSupport();
        ReflectionTestUtils.setField(support, "internalToken", INTERNAL_TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest();
        Assert.assertFalse(support.isAuthorized(request));
    }

    @Test
    public void internalCallbackAuth_acceptsValidToken() {
        InternalCallbackAuthSupport support = new InternalCallbackAuthSupport();
        ReflectionTestUtils.setField(support, "internalToken", INTERNAL_TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, INTERNAL_TOKEN);
        Assert.assertTrue(support.isAuthorized(request));
    }

    private GatewayUserResolver resolver() {
        GatewayUserResolver resolver = new GatewayUserResolver();
        ReflectionTestUtils.setField(resolver, "internalToken", INTERNAL_TOKEN);
        ReflectionTestUtils.setField(resolver, "identitySigningSecret", SECRET);
        return resolver;
    }

    private MockHttpServletRequest gatewayRequest(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        request.addHeader(CommonConstant.HEADER_INTERNAL_TOKEN, INTERNAL_TOKEN);
        request.addHeader(CommonConstant.HEADER_INTERNAL_JWT,
                InternalIdentityJwt.mint(SECRET, userId, "tester", "USER"));
        return request;
    }
}
