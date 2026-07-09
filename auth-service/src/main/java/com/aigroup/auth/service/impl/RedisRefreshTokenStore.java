package com.aigroup.auth.service.impl;

import com.aigroup.auth.service.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String REFRESH_TOKEN_KEY_PREFIX = "jwt:refresh:";
    private static final String USER_REFRESH_SET_PREFIX = "jwt:user:refresh:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void store(Long userId, String jti, Duration ttl) {
        if (userId == null || jti == null || jti.isBlank()) {
            return;
        }
        String tokenKey = REFRESH_TOKEN_KEY_PREFIX + jti;
        String userKey = USER_REFRESH_SET_PREFIX + userId;
        stringRedisTemplate.opsForValue().set(tokenKey, String.valueOf(userId), ttl);
        stringRedisTemplate.opsForSet().add(userKey, jti);
        stringRedisTemplate.expire(userKey, ttl);
    }

    @Override
    public boolean isActive(Long userId, String jti) {
        if (userId == null || jti == null || jti.isBlank()) {
            return false;
        }
        String tokenKey = REFRESH_TOKEN_KEY_PREFIX + jti;
        String storedUserId = stringRedisTemplate.opsForValue().get(tokenKey);
        return storedUserId != null && storedUserId.equals(String.valueOf(userId));
    }

    @Override
    public boolean consume(Long userId, String jti) {
        if (userId == null || jti == null || jti.isBlank()) {
            return false;
        }
        String tokenKey = REFRESH_TOKEN_KEY_PREFIX + jti;
        String userKey = USER_REFRESH_SET_PREFIX + userId;
        // Atomic check-and-delete: only the first caller receives the deleted value,
        // preventing a refresh token from being redeemed twice (replay window).
        String storedUserId = stringRedisTemplate.opsForValue().getAndDelete(tokenKey);
        boolean matched = storedUserId != null && storedUserId.equals(String.valueOf(userId));
        stringRedisTemplate.opsForSet().remove(userKey, jti);
        return matched;
    }

    @Override
    public void revoke(Long userId, String jti) {
        if (userId == null || jti == null || jti.isBlank()) {
            return;
        }
        String tokenKey = REFRESH_TOKEN_KEY_PREFIX + jti;
        String userKey = USER_REFRESH_SET_PREFIX + userId;
        stringRedisTemplate.delete(tokenKey);
        stringRedisTemplate.opsForSet().remove(userKey, jti);
    }

    @Override
    public void revokeAllForUser(Long userId) {
        if (userId == null) {
            return;
        }
        String userKey = USER_REFRESH_SET_PREFIX + userId;
        Set<String> members = stringRedisTemplate.opsForSet().members(userKey);
        if (members != null) {
            for (String jti : members) {
                if (jti == null || jti.isBlank()) {
                    continue;
                }
                stringRedisTemplate.delete(REFRESH_TOKEN_KEY_PREFIX + jti);
            }
        }
        stringRedisTemplate.delete(userKey);
    }

    @Override
    public Set<String> listActiveJtis(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Set<String> members = stringRedisTemplate.opsForSet().members(USER_REFRESH_SET_PREFIX + userId);
        return members == null ? Collections.emptySet() : members;
    }
}

