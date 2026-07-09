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
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT utility (generate / parse / validate). Secret is loaded from {@link JwtProperties}.
 */
@Component
public class JwtUtils {

    private final JwtProperties jwtProperties;
    private SecretKey signingKey;

    public JwtUtils(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    void init() {
        if (!StringUtils.hasText(jwtProperties.getSecret())) {
            throw new IllegalStateException("JWT secret is not configured (set JWT_SECRET env var)");
        }
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CommonConstant.TOKEN_CLAIM_USER_ID, userId);
        claims.put(CommonConstant.TOKEN_CLAIM_USERNAME, username);
        claims.put(CommonConstant.TOKEN_CLAIM_TYPE, CommonConstant.TOKEN_TYPE_ACCESS);
        return generateToken(claims, jwtProperties.getAccessExpirationMs());
    }

    public String generateToken(Long userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CommonConstant.TOKEN_CLAIM_USER_ID, userId);
        claims.put(CommonConstant.TOKEN_CLAIM_USERNAME, username);
        claims.put(CommonConstant.TOKEN_CLAIM_ROLE, role);
        claims.put(CommonConstant.TOKEN_CLAIM_TYPE, CommonConstant.TOKEN_TYPE_ACCESS);
        return generateToken(claims, jwtProperties.getAccessExpirationMs());
    }

    public String generateRefreshToken(Long userId) {
        return generateRefreshToken(userId, UUID.randomUUID().toString().replace("-", ""));
    }

    public String generateRefreshToken(Long userId, String jti) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CommonConstant.TOKEN_CLAIM_USER_ID, userId);
        claims.put(CommonConstant.TOKEN_CLAIM_JTI, jti);
        claims.put(CommonConstant.TOKEN_CLAIM_TYPE, CommonConstant.TOKEN_TYPE_REFRESH);
        return generateToken(claims, jwtProperties.getRefreshExpirationMs());
    }

    private String generateToken(Map<String, Object> claims, long expiration) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(signingKey)
                .compact();
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
        }
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        return claims.get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class);
    }

    public String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims.get(CommonConstant.TOKEN_CLAIM_USERNAME, String.class);
    }

    public String getRole(String token) {
        Claims claims = parseToken(token);
        return claims.get(CommonConstant.TOKEN_CLAIM_ROLE, String.class);
    }

    public String getJti(String token) {
        Claims claims = parseToken(token);
        return claims.get(CommonConstant.TOKEN_CLAIM_JTI, String.class);
    }

    /**
     * Token type ("access" / "refresh"). Legacy tokens issued before this claim
     * existed return {@code null}; the gateway treats only "access" as valid for API calls.
     */
    public String getTokenType(String token) {
        Claims claims = parseToken(token);
        return claims.get(CommonConstant.TOKEN_CLAIM_TYPE, String.class);
    }

    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    public String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith(CommonConstant.TOKEN_PREFIX)) {
            return authHeader.substring(CommonConstant.TOKEN_PREFIX.length());
        }
        throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
    }
}
