package com.aigroup.auth.service.impl;

import com.aigroup.auth.client.MemberClient;
import com.aigroup.auth.mapper.UserMapper;
import com.aigroup.auth.service.RefreshTokenStore;
import com.aigroup.common.config.JwtProperties;
import com.aigroup.common.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplLogoutTest {

    private JwtProperties jwtProperties;
    private JwtUtils jwtUtils;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RefreshTokenStore refreshTokenStore;
    private AuthServiceImpl authService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("0123456789abcdef0123456789abcdef");
        jwtProperties.setAccessExpirationMs(60_000L);
        jwtUtils = new JwtUtils(jwtProperties);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(jwtUtils, "init");
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        refreshTokenStore = mock(RefreshTokenStore.class);
        authService = new AuthServiceImpl(
                mock(UserMapper.class),
                jwtUtils,
                jwtProperties,
                mock(BCryptPasswordEncoder.class),
                redisTemplate,
                mock(MemberClient.class),
                refreshTokenStore
        );
    }

    @Test
    void invalidOrRefreshTokensDoNotWriteBlacklistOrRevokeSessions() {
        authService.logout("not-a-jwt");
        authService.logout(jwtUtils.generateRefreshToken(7L, "refresh-jti"));

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(refreshTokenStore, never()).revokeAllForUser(any());
    }

    @Test
    void validAccessTokenWritesDigestWithBoundedTtlAndRevokesRefreshSessions() {
        String token = jwtUtils.generateToken(7L, "tester", "USER");
        authService.logout(token);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("jwt:blacklist:" + jwtUtils.blacklistKey(token)), eq("1"), ttl.capture());
        assertTrue(ttl.getValue().toMillis() > 0);
        assertTrue(ttl.getValue().toMillis() <= jwtProperties.getAccessExpirationMs());
        verify(refreshTokenStore).revokeAllForUser(7L);
    }

}
