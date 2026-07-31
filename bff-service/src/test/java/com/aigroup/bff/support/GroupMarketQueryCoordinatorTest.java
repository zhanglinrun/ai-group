package com.aigroup.bff.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupMarketQueryCoordinatorTest {

    @Test
    void cachesSuccessfulMarketResponseForThePacingWindow() {
        AtomicLong now = new AtomicLong(0L);
        AtomicInteger calls = new AtomicInteger();
        GroupMarketQueryCoordinator coordinator = new GroupMarketQueryCoordinator(1200L, now::get, ignored -> {
        });

        Map<String, Object> first = coordinator.query(7L, "sku-a", () -> success(calls.incrementAndGet()));
        now.addAndGet(300L);
        Map<String, Object> second = coordinator.query(7L, "sku-a", () -> success(calls.incrementAndGet()));

        assertEquals(1, calls.get());
        assertEquals(first, second);
    }

    @Test
    void pacesDistinctSkuRequestsForTheSameUser() {
        AtomicLong now = new AtomicLong(0L);
        List<Long> waits = new ArrayList<>();
        GroupMarketQueryCoordinator coordinator = new GroupMarketQueryCoordinator(1200L, now::get, wait -> {
            waits.add(wait);
            now.addAndGet(wait);
        });

        coordinator.query(7L, "sku-a", () -> success(1));
        coordinator.query(7L, "sku-b", () -> success(2));

        assertEquals(List.of(1200L), waits);
    }

    @Test
    void doesNotCacheRateLimitedResponses() {
        AtomicLong now = new AtomicLong(0L);
        AtomicInteger calls = new AtomicInteger();
        GroupMarketQueryCoordinator coordinator = new GroupMarketQueryCoordinator(1200L, now::get, now::addAndGet);

        coordinator.query(7L, "sku-a", () -> {
            calls.incrementAndGet();
            return Map.of("code", "0006");
        });
        coordinator.query(7L, "sku-a", () -> success(calls.incrementAndGet()));

        assertEquals(2, calls.get());
    }

    private Map<String, Object> success(int call) {
        return Map.of("code", "0000", "data", Map.of("call", call));
    }
}
