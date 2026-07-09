package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.guard.PerUserConcurrencyLimiter;

/**
 * 单用户并发对话限流器测试。
 */
public class PerUserConcurrencyLimiterTest {

    @Test
    public void shouldRejectWhenExceedingPerUserLimit() {
        PerUserConcurrencyLimiter limiter = new PerUserConcurrencyLimiter(2);

        Assert.assertTrue(limiter.tryAcquire("u1"));
        Assert.assertTrue(limiter.tryAcquire("u1"));
        // 第三个并发超过上限，拒绝
        Assert.assertFalse(limiter.tryAcquire("u1"));
        Assert.assertEquals(2, limiter.currentInFlight("u1"));

        // 释放一个后可再次获取
        limiter.release("u1");
        Assert.assertEquals(1, limiter.currentInFlight("u1"));
        Assert.assertTrue(limiter.tryAcquire("u1"));
    }

    @Test
    public void shouldIsolateDifferentUsers() {
        PerUserConcurrencyLimiter limiter = new PerUserConcurrencyLimiter(1);
        Assert.assertTrue(limiter.tryAcquire("u1"));
        // 不同用户互不影响
        Assert.assertTrue(limiter.tryAcquire("u2"));
        Assert.assertFalse(limiter.tryAcquire("u1"));
    }

    @Test
    public void shouldDisableLimitWhenMaxNonPositive() {
        PerUserConcurrencyLimiter limiter = new PerUserConcurrencyLimiter(0);
        for (int i = 0; i < 100; i++) {
            Assert.assertTrue(limiter.tryAcquire("u1"));
        }
    }

    @Test
    public void releaseShouldNotDriveCountNegative() {
        PerUserConcurrencyLimiter limiter = new PerUserConcurrencyLimiter(3);
        limiter.release("u1");
        Assert.assertEquals(0, limiter.currentInFlight("u1"));
        Assert.assertTrue(limiter.tryAcquire("u1"));
        Assert.assertEquals(1, limiter.currentInFlight("u1"));
    }
}
