package com.aigroup.groupbuy.infrastructure.redis;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Occupies one team seat in a single Redis round-trip.
 * Keeps the historical {@code occupy = INCR + 1} comparison against
 * {@code target + recovery}.
 */
public final class TeamStockOccupyScript {

    public static final String LUA = """
            local recovery = tonumber(redis.call('GET', KEYS[2]) or '0')
            local occupy = redis.call('INCR', KEYS[1]) + 1
            if occupy > tonumber(ARGV[1]) + recovery then
              redis.call('DECR', KEYS[1])
              return 0
            end
            local lockKey = KEYS[1] .. '_' .. occupy
            local ok = redis.call('SET', lockKey, '1', 'NX', 'EX', ARGV[2])
            if not ok then
              redis.call('DECR', KEYS[1])
              return 0
            end
            return 1
            """;

    private TeamStockOccupyScript() {
    }

    public static boolean occupy(IRedisService redisService, String teamStockKey,
                                 String recoveryTeamStockKey, Integer target, Integer validTimeMinutes) {
        long lockTtlSeconds = TimeUnit.MINUTES.toSeconds((validTimeMinutes == null ? 0 : validTimeMinutes) + 60L);
        Long result = redisService.evalLong(
                LUA,
                List.of(teamStockKey, recoveryTeamStockKey),
                List.of(String.valueOf(target), String.valueOf(lockTtlSeconds)));
        return result != null && result == 1L;
    }
}
