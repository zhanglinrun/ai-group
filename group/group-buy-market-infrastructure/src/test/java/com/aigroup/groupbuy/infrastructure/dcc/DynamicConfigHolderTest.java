package com.aigroup.groupbuy.infrastructure.dcc;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DynamicConfigHolder 变更监听器测试：验证 {@code @DCCValue} 运行时动态更新的底层机制。
 */
public class DynamicConfigHolderTest {

    @Test
    public void listenerReceivesChangeOnPut() {
        DynamicConfigHolder holder = new DynamicConfigHolder();
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> receivedValue = new AtomicReference<>();
        AtomicInteger callCount = new AtomicInteger(0);

        holder.addListener((key, value) -> {
            receivedKey.set(key);
            receivedValue.set(value);
            callCount.incrementAndGet();
        });

        holder.put("rateLimiterSwitch", "close");

        Assert.assertEquals("rateLimiterSwitch", receivedKey.get());
        Assert.assertEquals("close", receivedValue.get());
        Assert.assertEquals(1, callCount.get());
    }

    @Test
    public void putAllTriggersListenersForEachKey() {
        DynamicConfigHolder holder = new DynamicConfigHolder();
        AtomicInteger callCount = new AtomicInteger(0);

        holder.addListener((key, value) -> callCount.incrementAndGet());

        Map<String, String> seed = new HashMap<>();
        seed.put("key1", "val1");
        seed.put("key2", "val2");
        seed.put("key3", "val3");
        holder.putAll(seed);

        Assert.assertEquals(3, callCount.get());
    }

    @Test
    public void listenerFailureDoesNotBlockUpdate() {
        DynamicConfigHolder holder = new DynamicConfigHolder();
        AtomicInteger goodListenerCalls = new AtomicInteger(0);

        // 第一个监听器抛异常，不应影响第二个监听器
        holder.addListener((key, value) -> { throw new RuntimeException("boom"); });
        holder.addListener((key, value) -> goodListenerCalls.incrementAndGet());

        holder.put("testKey", "testValue");

        Assert.assertEquals(1, goodListenerCalls.get());
        Assert.assertEquals("testValue", holder.get("testKey", "default"));
    }

    @Test
    public void getReturnsDefaultWhenKeyAbsent() {
        DynamicConfigHolder holder = new DynamicConfigHolder();
        Assert.assertEquals("fallback", holder.get("nonexistent", "fallback"));
    }

    @Test
    public void getReturnsUpdatedValueAfterPut() {
        DynamicConfigHolder holder = new DynamicConfigHolder();
        holder.put("switch", "open");
        Assert.assertEquals("open", holder.get("switch", "closed"));
        holder.put("switch", "close");
        Assert.assertEquals("close", holder.get("switch", "closed"));
    }
}
