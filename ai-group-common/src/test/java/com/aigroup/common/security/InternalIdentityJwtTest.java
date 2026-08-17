package com.aigroup.common.security;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalIdentityJwtTest {

    private static final String SECRET = "unit-test-identity-signing-secret!";

    @Test
    void mintAndVerifyRoundTrip() {
        String token = InternalIdentityJwt.mint(SECRET, "42", "alice", "user");
        InternalIdentityJwt.Claims claims = InternalIdentityJwt.verify(SECRET, token);

        assertNotNull(claims);
        assertEquals(42L, claims.userId());
        assertEquals("alice", claims.username());
        assertEquals("USER", claims.role());
        assertTrue(token.split("\\.").length == 3);
    }

    @Test
    void expiredTokenIsRejected() {
        Instant now = Instant.now();
        String token = InternalIdentityJwt.mint(
                SECRET, "7", "bob", "USER", now.minusSeconds(120), now.minusSeconds(60));
        assertNull(InternalIdentityJwt.verify(SECRET, token));
    }

    @Test
    void wrongSecretIsRejected() {
        String token = InternalIdentityJwt.mint(SECRET, "1", "alice", "USER");
        assertNull(InternalIdentityJwt.verify("other-identity-signing-secret-32b!", token));
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("1")
                .issuer(InternalIdentityJwt.ISSUER)
                .audience("other-audience")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .claim("username", "alice")
                .claim("role", "USER")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(InternalIdentityJwt.signingKey(SECRET)));
        assertNull(InternalIdentityJwt.verify(SECRET, jwt.serialize()));
    }

    @Test
    void blankSecretDoesNotMint() {
        assertEquals("", InternalIdentityJwt.mint("  ", "1", "alice", "USER"));
        assertNull(InternalIdentityJwt.verify(SECRET, ""));
        assertNull(InternalIdentityJwt.verify("", "header.payload.sig"));
    }
}
