package com.aigroup.auth.service;

import java.time.Duration;
import java.util.Set;

public interface RefreshTokenStore {

    void store(Long userId, String jti, Duration ttl);

    boolean isActive(Long userId, String jti);

    /**
     * Atomically validate and revoke a refresh token in a single step (replay-safe).
     * Returns true only for the first caller; concurrent callers with the same jti get false.
     */
    boolean consume(Long userId, String jti);

    void revoke(Long userId, String jti);

    void revokeAllForUser(Long userId);

    Set<String> listActiveJtis(Long userId);
}

