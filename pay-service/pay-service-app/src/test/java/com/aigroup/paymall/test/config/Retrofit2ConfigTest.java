package com.aigroup.paymall.test.config;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.paymall.config.Retrofit2Config;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * pay → group/member 内部调用必须附带 X-Internal-Token。
 * 迁移到 OpenFeign 后，token 由 {@code internalTokenRequestInterceptor} 注入。
 */
public class Retrofit2ConfigTest {

    @After
    public void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void interceptorAttachesInternalTokenWhenConfigured() {
        RequestInterceptor interceptor = buildInterceptor("secret-internal-token");
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertEquals("secret-internal-token", firstHeader(template, Retrofit2Config.HEADER_INTERNAL_TOKEN));
    }

    @Test
    public void interceptorOmitsTokenWhenBlank() {
        RequestInterceptor interceptor = buildInterceptor("");
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertNull(template.headers().get(Retrofit2Config.HEADER_INTERNAL_TOKEN));
    }

    @Test
    public void shouldAttachSingleInternalTokenOnLockQuerySettlementAndRefund() {
        RequestInterceptor interceptor = buildInterceptor("secret-internal-token");
        String[] paths = {
                "/api/v1/gbm/trade/lock_market_pay_order",
                "/api/v1/gbm/trade/query_market_pay_order",
                "/api/v1/gbm/trade/settlement_market_pay_order",
                "/api/v1/gbm/trade/refund_market_pay_order"
        };
        for (String path : paths) {
            RequestTemplate template = new RequestTemplate();
            template.uri(path);
            interceptor.apply(template);
            assertEquals("secret-internal-token", firstHeader(template, Retrofit2Config.HEADER_INTERNAL_TOKEN));
            assertEquals(1, template.headers().get(Retrofit2Config.HEADER_INTERNAL_TOKEN).size());
        }
    }

    @Test
    public void interceptorForwardsIncomingJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstant.HEADER_INTERNAL_JWT, "header.payload.sig");
        request.addHeader(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RequestInterceptor interceptor = buildInterceptor("secret-internal-token");
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertEquals("secret-internal-token", firstHeader(template, Retrofit2Config.HEADER_INTERNAL_TOKEN));
        assertEquals("header.payload.sig", firstHeader(template, CommonConstant.HEADER_INTERNAL_JWT));
        assertEquals("true", firstHeader(template, CommonConstant.HEADER_GATEWAY_REQUEST));
    }

    private String firstHeader(RequestTemplate template, String name) {
        Collection<String> values = template.headers().get(name);
        assertNotNull(values);
        assertTrue(values.iterator().hasNext());
        return values.iterator().next();
    }

    private RequestInterceptor buildInterceptor(String token) {
        Retrofit2Config config = new Retrofit2Config();
        ReflectionTestUtils.setField(config, "internalToken", token);
        return config.internalTokenRequestInterceptor();
    }
}
