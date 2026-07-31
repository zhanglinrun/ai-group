package com.aigroup.bff.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Coordinates BFF reads against Group's per-user market endpoint limit.
 *
 * <p>The frozen Group service permits one market-config request per user per second.
 * Pricing needs several SKU configs, so BFF must sequence uncached reads and serve the
 * immediately repeated view request from a short-lived successful-response cache.</p>
 */
@Component
public class GroupMarketQueryCoordinator {

    private final long minimumIntervalMillis;
    private final LongSupplier clockMillis;
    private final LongConsumer sleeperMillis;
    private final ConcurrentMap<Long, Object> userLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> nextAllowedAtMillis = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry> successfulResponseCache = new ConcurrentHashMap<>();

    @Autowired
    public GroupMarketQueryCoordinator(
            @Value("${ai-group.group.query-min-interval-millis:1200}") long minimumIntervalMillis) {
        this(minimumIntervalMillis, System::currentTimeMillis, GroupMarketQueryCoordinator::sleep);
    }

    GroupMarketQueryCoordinator(long minimumIntervalMillis, LongSupplier clockMillis, LongConsumer sleeperMillis) {
        if (minimumIntervalMillis < 0) {
            throw new IllegalArgumentException("minimumIntervalMillis must not be negative");
        }
        this.minimumIntervalMillis = minimumIntervalMillis;
        this.clockMillis = clockMillis;
        this.sleeperMillis = sleeperMillis;
    }

    public Map<String, Object> query(long userId, String goodsId, Supplier<Map<String, Object>> remoteQuery) {
        String cacheKey = userId + "\u0000" + goodsId;
        CacheEntry cached = successfulResponseCache.get(cacheKey);
        long now = clockMillis.getAsLong();
        if (cached != null && cached.expiresAtMillis() > now) {
            return new HashMap<>(cached.response());
        }

        Object userLock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (userLock) {
            now = clockMillis.getAsLong();
            cached = successfulResponseCache.get(cacheKey);
            if (cached != null && cached.expiresAtMillis() > now) {
                return new HashMap<>(cached.response());
            }

            long nextAllowedAt = nextAllowedAtMillis.getOrDefault(userId, 0L);
            if (now < nextAllowedAt) {
                sleeperMillis.accept(nextAllowedAt - now);
                now = clockMillis.getAsLong();
            }
            nextAllowedAtMillis.put(userId, now + minimumIntervalMillis);

            Map<String, Object> response = remoteQuery.get();
            if (isSuccessful(response)) {
                successfulResponseCache.put(cacheKey,
                        new CacheEntry(new HashMap<>(response), now + minimumIntervalMillis));
            }
            return response;
        }
    }

    private boolean isSuccessful(Map<String, Object> response) {
        return response != null && "0000".equals(String.valueOf(response.get("code")));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while pacing Group market requests", ex);
        }
    }

    private record CacheEntry(Map<String, Object> response, long expiresAtMillis) {
    }
}
