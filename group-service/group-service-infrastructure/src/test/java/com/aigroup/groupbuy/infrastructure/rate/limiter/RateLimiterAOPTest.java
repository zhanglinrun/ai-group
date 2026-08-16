package com.aigroup.groupbuy.infrastructure.rate.limiter;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class RateLimiterAOPTest {

    @After
    public void clearUser() {
        RequestUserContext.clear();
    }

    @Test
    public void userIdKeyPrefersJwtBinding() {
        RequestUserContext.bind(42L, "alice", "USER");
        RateLimiterAOP aop = new RateLimiterAOP(mock(IRedisService.class));
        record Body(String userId) {
        }
        assertEquals("42", aop.resolveKey("userId", new Object[]{new Body("999")}));
    }

    @Test
    public void userIdKeyFallsBackToBodyWhenUnbound() {
        RateLimiterAOP aop = new RateLimiterAOP(mock(IRedisService.class));
        record Body(String userId) {
        }
        assertEquals("999", aop.resolveKey("userId", new Object[]{new Body("999")}));
    }
}
