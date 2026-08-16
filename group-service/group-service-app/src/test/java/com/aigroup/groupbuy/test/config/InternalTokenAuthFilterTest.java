package com.aigroup.groupbuy.test.config;

import com.aigroup.groupbuy.config.InternalTokenAuthFilter;
import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * group 内部 token 过滤器：trade fail-closed、DCC、回滚开关。
 */
public class InternalTokenAuthFilterTest {

    @Test
    public void shouldFailStartupWhenAuthEnabledButTokenBlank() {
        InternalTokenAuthFilter filter = newFilter(true, "");
        try {
            filter.afterPropertiesSet();
            fail("blank token with auth enabled must fail startup");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void shouldFailStartupWhenDccTokenBlankEvenIfTradeAuthDisabled() {
        InternalTokenAuthFilter filter = newFilter(false, "");
        try {
            filter.afterPropertiesSet();
            fail("DCC must never start without an internal token");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void shouldRejectTradeWithoutOrWithWrongToken() throws Exception {
        InternalTokenAuthFilter filter = newFilter(true, "secret");
        filter.afterPropertiesSet();

        assertEquals(403, statusFor(filter, "/api/v1/gbm/trade/lock_market_pay_order", null));
        assertEquals(403, statusFor(filter, "/api/v1/gbm/trade/settlement_market_pay_order", "wrong"));
        assertEquals(200, statusFor(filter, "/api/v1/gbm/trade/refund_market_pay_order", "secret"));
    }

    @Test
    public void shouldProtectIndexAndGroupWhenAuthEnabled() throws Exception {
        InternalTokenAuthFilter filter = newFilter(true, "secret");
        filter.afterPropertiesSet();
        assertEquals(403, statusFor(filter, "/api/v1/gbm/index/query_group_buy_market_config", null));
        assertEquals(403, statusFor(filter, "/api/group/activities", null));
        assertEquals(200, statusFor(filter, "/api/v1/gbm/index/query_group_buy_market_config", "secret"));
        assertEquals(200, statusFor(filter, "/api/group/activities", "secret"));
    }

    @Test
    public void shouldAllowTradeWhenAuthDisabled() throws Exception {
        InternalTokenAuthFilter filter = newFilter(false, "secret");
        filter.afterPropertiesSet();
        assertEquals(200, statusFor(filter, "/api/v1/gbm/trade/lock_market_pay_order", null));
    }

    @Test
    public void shouldProtectDccEvenWhenAuthDisabled() throws Exception {
        InternalTokenAuthFilter filter = newFilter(false, "secret");
        filter.afterPropertiesSet();
        assertEquals(403, statusFor(filter, "/api/v1/gbm/dcc/update_config", null));
        assertEquals(200, statusFor(filter, "/api/v1/gbm/dcc/update_config", "secret"));
    }

    private InternalTokenAuthFilter newFilter(boolean authEnabled, String token) {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter();
        ReflectionTestUtils.setField(filter, "authEnabled", authEnabled);
        ReflectionTestUtils.setField(filter, "internalToken", token);
        return filter;
    }

    private int statusFor(InternalTokenAuthFilter filter, String path, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (token != null) {
            request.addHeader(InternalTokenAuthFilter.HEADER_INTERNAL_TOKEN, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        // filter sets 403 and returns; otherwise MockFilterChain leaves status 200
        return response.getStatus();
    }
}
