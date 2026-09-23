package com.aigroup.groupbuy.infrastructure.cache;

import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Redis-only marketing-config Cache Aside, aligned with campus-dash detail cache:
 * shards, TTL jitter, null markers, Single-Flight miss coalescing, delayed double-delete.
 * No process-local Caffeine/Map layer — MySQL remains authoritative.
 */
@Slf4j
@Component
public class MarketConfigCacheSupport {

    public static final String NULL_MARKER = "__MARKET_CONFIG_NULL__";
    private static final String EVICT_QUEUE = "group_buy_market_config_cache_evict";

    private final IRedisService redisService;
    private final int shardCount;
    private final long redisTtlMs;
    private final long ttlJitterMs;
    private final long nullTtlMs;
    private final long delayedEvictMs;

    private RBlockingQueue<String> evictQueue;
    private RDelayedQueue<String> delayedEvictQueue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumer;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong nullHits = new AtomicLong();
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    public MarketConfigCacheSupport(
            IRedisService redisService,
            @Value("${group.market-config.shard-count:4}") int shardCount,
            @Value("${group.market-config.redis-ttl-ms:600000}") long redisTtlMs,
            @Value("${group.market-config.ttl-jitter-ms:120000}") long ttlJitterMs,
            @Value("${group.market-config.null-ttl-ms:60000}") long nullTtlMs,
            @Value("${group.market-config.delayed-evict-ms:500}") long delayedEvictMs) {
        this.redisService = redisService;
        this.shardCount = Math.max(1, shardCount);
        this.redisTtlMs = Math.max(1L, redisTtlMs);
        this.ttlJitterMs = Math.max(0L, ttlJitterMs);
        this.nullTtlMs = Math.max(1L, nullTtlMs);
        this.delayedEvictMs = Math.max(0L, delayedEvictMs);
    }

    @PostConstruct
    void startDelayedEvictConsumer() {
        evictQueue = redisService.getBlockingQueue(EVICT_QUEUE);
        delayedEvictQueue = redisService.getDelayedQueue(evictQueue);
        running.set(true);
        consumer = new Thread(this::consumeDelayedEvicts, "market-config-cache-evict");
        consumer.setDaemon(true);
        consumer.start();
    }

    @PreDestroy
    void stopDelayedEvictConsumer() {
        running.set(false);
        if (consumer != null) {
            consumer.interrupt();
        }
    }

    public <T> LookupResult<T> get(String logicalKey) {
        if (StringUtils.isBlank(logicalKey)) {
            return LookupResult.miss();
        }
        String shardKey = shardKey(logicalKey, ThreadLocalRandom.current().nextInt(shardCount));
        Object raw = redisService.getValue(shardKey);
        if (raw == null) {
            raw = redisService.getValue(logicalKey);
            if (raw == null) {
                misses.incrementAndGet();
                return LookupResult.miss();
            }
        }
        if (isNullMarker(raw)) {
            nullHits.incrementAndGet();
            return LookupResult.nullCached();
        }
        @SuppressWarnings("unchecked")
        T value = (T) raw;
        hits.incrementAndGet();
        return LookupResult.hit(value);
    }

    /**
     * Cache Aside load: hit/null from Redis, else Single-Flight one DB round-trip + backfill.
     */
    public <T> T loadThrough(String logicalKey, Supplier<T> dbFallback, Runnable onDbLoad) {
        return loadThrough(logicalKey, dbFallback, onDbLoad, -1L);
    }

    /**
     * @param ttlMs positive overrides default jittered config TTL; {@code <=0} uses default
     */
    public <T> T loadThrough(String logicalKey, Supplier<T> dbFallback, Runnable onDbLoad, long ttlMs) {
        LookupResult<T> cached = get(logicalKey);
        if (cached.isHit()) {
            return cached.value();
        }
        if (cached.isNullCached()) {
            return null;
        }
        return coalesceMiss(logicalKey, () -> {
            if (onDbLoad != null) {
                onDbLoad.run();
            }
            T loaded = dbFallback.get();
            if (loaded == null) {
                putNull(logicalKey);
            } else if (ttlMs > 0L) {
                put(logicalKey, loaded, ttlMs);
            } else {
                put(logicalKey, loaded);
            }
            return loaded;
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T coalesceMiss(String logicalKey, Supplier<T> loader) {
        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(logicalKey, created);
        if (existing == null) {
            try {
                T loaded = loader.get();
                created.complete(loaded);
                return loaded;
            } catch (Throwable ex) {
                created.completeExceptionally(ex);
                if (ex instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (ex instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("market config cache load failed key=" + logicalKey, ex);
            } finally {
                inFlight.remove(logicalKey, created);
            }
        }
        try {
            return (T) existing.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted loading market config key=" + logicalKey, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed loading market config key=" + logicalKey, cause);
        }
    }

    public Object peekAnyShard(String logicalKey) {
        if (StringUtils.isBlank(logicalKey)) {
            return null;
        }
        for (int i = 0; i < shardCount; i++) {
            Object raw = redisService.getValue(shardKey(logicalKey, i));
            if (raw != null) {
                return raw;
            }
        }
        return redisService.getValue(logicalKey);
    }

    public <T> void put(String logicalKey, T value) {
        put(logicalKey, value, jitteredTtlMs());
    }

    public <T> void put(String logicalKey, T value, long ttlMs) {
        if (StringUtils.isBlank(logicalKey) || value == null) {
            return;
        }
        removeLegacy(logicalKey);
        long ttl = Math.max(1L, ttlMs);
        for (int i = 0; i < shardCount; i++) {
            redisService.setValue(shardKey(logicalKey, i), value, ttl);
        }
    }

    public void putNull(String logicalKey) {
        if (StringUtils.isBlank(logicalKey)) {
            return;
        }
        removeLegacy(logicalKey);
        for (int i = 0; i < shardCount; i++) {
            redisService.setValue(shardKey(logicalKey, i), NULL_MARKER, nullTtlMs);
        }
    }

    public void evict(String logicalKey) {
        if (StringUtils.isBlank(logicalKey)) {
            return;
        }
        removeLegacy(logicalKey);
        for (int i = 0; i < shardCount; i++) {
            redisService.remove(shardKey(logicalKey, i));
        }
    }

    /** Immediate delete + delayed second delete (campus-dash double-delete). */
    public void evictNowAndDelayed(String... logicalKeys) {
        if (logicalKeys == null) {
            return;
        }
        for (String key : logicalKeys) {
            if (StringUtils.isBlank(key)) {
                continue;
            }
            evict(key);
            scheduleDelayedEvict(key);
        }
    }

    void scheduleDelayedEvict(String logicalKey) {
        if (delayedEvictMs <= 0L) {
            evict(logicalKey);
            return;
        }
        if (delayedEvictQueue == null) {
            log.warn("delayed evict queue unavailable, immediate second evict key={}", logicalKey);
            evict(logicalKey);
            return;
        }
        delayedEvictQueue.offer(logicalKey, delayedEvictMs, TimeUnit.MILLISECONDS);
    }

    private void consumeDelayedEvicts() {
        while (running.get()) {
            try {
                String key = evictQueue.poll(1, TimeUnit.SECONDS);
                if (key != null) {
                    evict(key);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("market config delayed evict failed", ex);
            }
        }
    }

    public static boolean isNullMarker(Object raw) {
        return raw instanceof String marker && NULL_MARKER.equals(marker);
    }

    public int shardCount() {
        return shardCount;
    }

    long jitteredTtlMs() {
        if (ttlJitterMs <= 0L) {
            return redisTtlMs;
        }
        long delta = ThreadLocalRandom.current().nextLong(-ttlJitterMs, ttlJitterMs + 1);
        return Math.max(60_000L, redisTtlMs + delta);
    }

    public CacheStats snapshot() {
        return new CacheStats(hits.get(), misses.get(), nullHits.get());
    }

    public CacheStats snapshotAndReset() {
        return new CacheStats(hits.getAndSet(0), misses.getAndSet(0), nullHits.getAndSet(0));
    }

    public record CacheStats(long hits, long misses, long nullHits) {
        public long lookups() {
            return hits + misses + nullHits;
        }

        public double hitRate() {
            long total = lookups();
            return total == 0 ? 0.0d : (hits + nullHits) * 1.0d / total;
        }
    }

    String shardKey(String logicalKey, int shard) {
        return logicalKey + ":s" + shard;
    }

    private void removeLegacy(String logicalKey) {
        redisService.remove(logicalKey);
    }

    public static final class LookupResult<T> {
        private enum Kind { MISS, NULL_CACHED, HIT }

        private final Kind kind;
        private final T value;

        private LookupResult(Kind kind, T value) {
            this.kind = kind;
            this.value = value;
        }

        public static <T> LookupResult<T> miss() {
            return new LookupResult<>(Kind.MISS, null);
        }

        public static <T> LookupResult<T> nullCached() {
            return new LookupResult<>(Kind.NULL_CACHED, null);
        }

        public static <T> LookupResult<T> hit(T value) {
            return new LookupResult<>(Kind.HIT, Objects.requireNonNull(value));
        }

        public boolean isMiss() {
            return kind == Kind.MISS;
        }

        public boolean isNullCached() {
            return kind == Kind.NULL_CACHED;
        }

        public boolean isHit() {
            return kind == Kind.HIT;
        }

        public T value() {
            return value;
        }
    }
}
