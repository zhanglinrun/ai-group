package com.aigroup.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Short-lived HS256 identity JWT minted by Gateway after Sa-Token validation.
 * This is not the browser session token.
 */
public final class InternalIdentityJwt {

    public static final String ISSUER = "ai-group-gateway";
    public static final String AUDIENCE = "ai-group-internal";
    public static final long TTL_SECONDS = 60;
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_ROLE = "role";

    public record Claims(long userId, String username, String role) {
    }

    private InternalIdentityJwt() {
    }

    public static String mint(String secret, String userId, String username, String role) {
        Instant now = Instant.now();
        return mint(secret, userId, username, role, now, now.plusSeconds(TTL_SECONDS));
    }

    static String mint(String secret, String userId, String username, String role,
                       Instant issuedAt, Instant expiresAt) {
        if (secret == null || secret.isBlank() || userId == null || userId.isBlank()) {
            return "";
        }
        String normalizedRole = role == null || role.isBlank() ? "USER" : role.toUpperCase(Locale.ROOT);
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(userId)
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString())
                    .claim(CLAIM_USERNAME, username)
                    .claim(CLAIM_ROLE, normalizedRole)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(hmacKey(secret)));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("cannot mint Gateway identity JWT", exception);
        }
    }

    public static Claims verify(String secret, String token) {
        if (secret == null || secret.isBlank() || token == null || token.isBlank()) {
            return null;
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(hmacKey(secret)))) {
                return null;
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            new DefaultJWTClaimsVerifier<>(
                    new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).build(),
                    Set.of("sub", "exp", "iat")
            ).verify(claims, null);
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return null;
            }
            String role = stringClaim(claims, CLAIM_ROLE);
            return new Claims(
                    Long.parseLong(subject),
                    stringClaim(claims, CLAIM_USERNAME),
                    role == null || role.isBlank() ? "USER" : role.toUpperCase(Locale.ROOT)
            );
        } catch (NumberFormatException | JOSEException | java.text.ParseException | BadJWTException ignored) {
            return null;
        }
    }

    /**
     * HS256 requires a 256-bit key. Hash the configured secret so existing
     * env values of any length still produce a spec-compliant HMAC key.
     */
    static byte[] hmacKey(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for identity JWT keys", exception);
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value == null ? null : String.valueOf(value);
    }
}
