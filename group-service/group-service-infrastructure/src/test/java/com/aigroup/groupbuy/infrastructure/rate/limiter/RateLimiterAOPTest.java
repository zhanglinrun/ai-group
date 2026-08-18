package com.aigroup.groupbuy.infrastructure.rate.limiter;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import com.aigroup.groupbuy.types.annotations.RateLimiterAccessInterceptor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    public void redisFailureFailsOpenSoMarketQueryStillRuns() throws Throwable {
        IRedisService redis = mock(IRedisService.class);
        when(redis.evalLong(any(), any(), any())).thenThrow(new RuntimeException("eval failed"));
        RateLimiterAOP aop = new RateLimiterAOP(redis);
        Field switchField = RateLimiterAOP.class.getDeclaredField("rateLimiterSwitch");
        switchField.setAccessible(true);
        switchField.set(aop, "open");

        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        when(jp.getArgs()).thenReturn(new Object[]{new Body("8")});
        when(jp.proceed()).thenReturn("ok");
        RateLimiterAccessInterceptor annotation = mock(RateLimiterAccessInterceptor.class);
        when(annotation.key()).thenReturn("userId");
        when(annotation.permitsPerSecond()).thenReturn(1.0d);
        when(annotation.blacklistCount()).thenReturn(0.0d);

        assertEquals("ok", aop.doRouter(jp, annotation));
        verify(jp).proceed();
    }

    record Body(String userId) {
    }
}
