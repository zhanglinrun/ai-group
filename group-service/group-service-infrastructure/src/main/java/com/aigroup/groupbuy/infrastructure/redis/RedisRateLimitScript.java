package com.aigroup.groupbuy.infrastructure.redis;

import java.util.List;

/** Cluster-wide fixed-window limiter and 24h blacklist counters. */
public final class RedisRateLimitScript {

    public static final String TRY_ACQUIRE = """
        local limit = tonumber(ARGV[1])
        local ttl = tonumber(ARGV[2])
        if not limit or not ttl then
          return 1
        end
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('PEXPIRE', KEYS[1], ttl)
        end
        if current > limit then
          return 0
        end
        return 1
            """;

    public static final String BUMP_BLACKLIST = """
        local ttl = tonumber(ARGV[1])
        if not ttl then
          return 0
        end
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('EXPIRE', KEYS[1], ttl)
        end
        return current
            """;

    private RedisRateLimitScript() {
    }

    public static boolean tryAcquire(IRedisService redis, String key, int limit, long windowMs) {
        Long allowed = redis.evalLong(TRY_ACQUIRE, List.of(key),
                List.of(String.valueOf(limit), String.valueOf(windowMs)));
        return allowed != null && allowed == 1L;
    }

    public static long bumpBlacklist(IRedisService redis, String key, long ttlSeconds) {
        Long count = redis.evalLong(BUMP_BLACKLIST, List.of(key), List.of(String.valueOf(ttlSeconds)));
        return count == null ? 0L : count;
    }

    public static long blacklistCount(IRedisService redis, String key) {
        Long value = redis.getAtomicLong(key);
        return value == null ? 0L : value;
    }
}
