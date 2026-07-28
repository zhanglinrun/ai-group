package com.aigroup.common.utils;

import com.aigroup.common.config.JwtProperties;
import com.aigroup.common.exception.TokenException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilsTest {

    @Test
    void rejectsWeakSigningSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("too-short");
        assertThrows(IllegalStateException.class, () -> newJwtUtils(properties));
    }

    @Test
    void acceptsOnlyAccessTokensForApiAuthentication() {
        JwtUtils jwtUtils = newJwtUtils(properties());
        String accessToken = jwtUtils.generateToken(7L, "tester", "USER");
        String refreshToken = jwtUtils.generateRefreshToken(7L, "refresh-jti");

        assertEquals(7L, jwtUtils.parseAccessToken(accessToken).get("userId", Long.class));
        assertThrows(TokenException.class, () -> jwtUtils.parseAccessToken(refreshToken));
    }

    @Test
    void blacklistDigestIsStableAndDoesNotContainTheToken() {
        JwtUtils jwtUtils = newJwtUtils(properties());
        String first = jwtUtils.blacklistKey("sample-token");

        assertEquals(first, jwtUtils.blacklistKey("sample-token"));
        assertEquals(64, first.length());
        assertNotEquals("sample-token", first);
    }

    private JwtProperties properties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        return properties;
    }

    private JwtUtils newJwtUtils(JwtProperties properties) {
        JwtUtils jwtUtils = new JwtUtils(properties);
        jwtUtils.init();
        return jwtUtils;
    }
}
