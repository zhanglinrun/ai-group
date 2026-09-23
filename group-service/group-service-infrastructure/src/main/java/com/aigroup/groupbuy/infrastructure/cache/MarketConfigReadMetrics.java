package com.aigroup.groupbuy.infrastructure.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Request-level cache accounting aligned with campus-dash GetErrandDetailUseCase:
 * hitRate = cacheHitCount / requestCount; dbLoadCount is detail/config DB round-trips.
 */
@Component
public class MarketConfigReadMetrics {

    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong cacheHitCount = new AtomicLong();
    private final AtomicLong dbLoadCount = new AtomicLong();

    public void recordRequest() {
        requestCount.incrementAndGet();
    }

    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
    }

    public void recordDbLoad() {
        dbLoadCount.incrementAndGet();
    }

    public long requestCount() {
        return requestCount.get();
    }

    public long cacheHitCount() {
        return cacheHitCount.get();
    }

    public long dbLoadCount() {
        return dbLoadCount.get();
    }

    /** Same formula as campus-dash: hits / requests. */
    public double hitRate() {
        long total = requestCount.get();
        return total == 0 ? 0.0d : (double) cacheHitCount.get() / total;
    }

    public Snapshot snapshot() {
        return new Snapshot(requestCount.get(), cacheHitCount.get(), dbLoadCount.get(), hitRate());
    }

    public Snapshot snapshotAndReset() {
        Snapshot snap = new Snapshot(
                requestCount.getAndSet(0),
                cacheHitCount.getAndSet(0),
                dbLoadCount.getAndSet(0),
                0.0d);
        double rate = snap.requestCount() == 0 ? 0.0d
                : (double) snap.cacheHitCount() / snap.requestCount();
        return new Snapshot(snap.requestCount(), snap.cacheHitCount(), snap.dbLoadCount(), rate);
    }

    public record Snapshot(long requestCount, long cacheHitCount, long dbLoadCount, double hitRate) {
    }
}
