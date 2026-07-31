package com.aigroup.common.utils;

import com.aigroup.common.config.JwtProperties;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.constant.ErrorCodeEnum;
import com.aigroup.common.exception.TokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** HMAC JWT helper shared by the resume-project services. */
@Component
public class JwtUtils {

    public static final int MAX_TOKEN_LENGTH = 4096;
    private static final int MIN_HMAC_SECRET_BYTES = 32;

    private final JwtProperties jwtProperties;
    private SecretKey signingKey;

    public JwtUtils(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    void init() {
        String secret = jwtProperties.getSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("JWT secret is not configured (set JWT_SECRET env var)");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_HMAC_SECRET_BYTES
                || "change-me-to-a-long-random-secret".equals(secret)
                || secret.contains("change-in-prod")) {
            throw new IllegalStateException("JWT secret must be a random value of at least 32 bytes");
        }
        signingKey = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generateToken(Long userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CommonConstant.TOKEN_CLAIM_USER_ID, userId);
        claims.put(CommonConstant.TOKEN_CLAIM_USERNAME, username);
        claims.put(CommonConstant.TOKEN_CLAIM_ROLE, role);
        claims.put(CommonConstant.TOKEN_CLAIM_TYPE, CommonConstant.TOKEN_TYPE_ACCESS);
        return generate(claims, jwtProperties.getAccessExpirationMs());
    }

    public String generateRefreshToken(Long userId) {
        return generateRefreshToken(userId, UUID.randomUUID().toString().replace("-", ""));
    }

    public String generateRefreshToken(Long userId, String jti) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CommonConstant.TOKEN_CLAIM_USER_ID, userId);
        claims.put(CommonConstant.TOKEN_CLAIM_JTI, jti);
        claims.put(CommonConstant.TOKEN_CLAIM_TYPE, CommonConstant.TOKEN_TYPE_REFRESH);
        return generate(claims, jwtProperties.getRefreshExpirationMs());
    }

    private String generate(Map<String, Object> claims, long expiration) {
        Date now = new Date();
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expiration))
                .signWith(signingKey)
                .compact();
    }

    public Claims parseToken(String token) {
        try {
            if (!StringUtils.hasText(token) || token.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException("invalid token length");
            }
            return Jwts.parserBuilder().setSigningKey(signingKey).build().parseClaimsJws(token).getBody();
        } catch (Exception ex) {
            throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
        }
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public Claims parseAccessToken(String token) {
        Claims claims = parseToken(token);
        if (!CommonConstant.TOKEN_TYPE_ACCESS.equals(claims.get(CommonConstant.TOKEN_CLAIM_TYPE, String.class))
                || claims.getExpiration() == null || !claims.getExpiration().after(new Date())) {
            throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
        }
        return claims;
    }

    public Claims parseRefreshToken(String token) {
        Claims claims = parseToken(token);
        if (!CommonConstant.TOKEN_TYPE_REFRESH.equals(claims.get(CommonConstant.TOKEN_CLAIM_TYPE, String.class))) {
            throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
        }
        return claims;
    }

    public String blacklistKey(String token) {
        if (!StringUtils.hasText(token) || token.length() > MAX_TOKEN_LENGTH) {
            throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public long remainingTtlMillis(Claims claims, long configuredMaxTtlMillis) {
        long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
        long upperBound = configuredMaxTtlMillis > 0 ? configuredMaxTtlMillis : remaining;
        return Math.max(1_000L, Math.min(remaining, upperBound));
    }

    public Long getUserId(Claims claims) {
        return claims == null ? null : claims.get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class);
    }

    public Long getUserId(String token) {
        return getUserId(parseToken(token));
    }

    public String getJti(Claims claims) {
        return claims == null ? null : claims.get(CommonConstant.TOKEN_CLAIM_JTI, String.class);
    }

    public String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith(CommonConstant.TOKEN_PREFIX)) {
            return authHeader.substring(CommonConstant.TOKEN_PREFIX.length());
        }
        throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
    }
}
