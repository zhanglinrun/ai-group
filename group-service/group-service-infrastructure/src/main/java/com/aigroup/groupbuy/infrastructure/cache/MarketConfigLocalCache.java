package com.aigroup.groupbuy.infrastructure.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Small L1 cache for read-mostly group-buy configuration.
 *
 * Redis remains the shared cache. This layer avoids a round trip to Redis for
 * every lock request while bounding cross-instance configuration staleness.
 */
@Component
public class MarketConfigLocalCache {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlNanos;

    public MarketConfigLocalCache(
            @Value("${group.market-config.local-cache-ttl-ms:1000}") long ttlMillis) {
        this.ttlNanos = ttlMillis <= 0 ? 0L : ttlMillis * 1_000_000L;
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String key, Supplier<T> loader) {
        if (ttlNanos == 0L) {
            return loader.get();
        }
        long now = System.nanoTime();
        Entry cached = entries.get(key);
        if (cached != null && now < cached.expiresAtNanos()) {
            return (T) cached.value();
        }

        T loaded = loader.get();
        if (loaded != null) {
            entries.put(key, new Entry(loaded, now + ttlNanos));
        } else {
            entries.remove(key);
        }
        return loaded;
    }

    public void invalidate(String key) {
        entries.remove(key);
    }

    private record Entry(Object value, long expiresAtNanos) {
    }
}
