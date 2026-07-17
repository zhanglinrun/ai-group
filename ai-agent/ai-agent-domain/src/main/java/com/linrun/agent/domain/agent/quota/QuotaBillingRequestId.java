package com.linrun.agent.domain.agent.quota;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable member idempotency keys and immutable reserve-command fingerprints. */
public final class QuotaBillingRequestId {

    public static final int MAX_LENGTH = 64;

    private QuotaBillingRequestId() {
    }

    public static String normalize(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("billingRequestId must not be blank");
        }
        String normalized = requestId.trim();
        return normalized.length() <= MAX_LENGTH ? normalized : sha256(normalized);
    }

    /** Must stay byte-for-byte aligned with member-service freezeRequestFingerprint. */
    public static String fingerprint(Long userId,
                                     String abilityCode,
                                     long requestedMicrocredits,
                                     long minimumMicrocredits) {
        return sha256(String.valueOf(userId) + '\n'
                + requestedMicrocredits + '\n'
                + minimumMicrocredits + '\n'
                + String.valueOf(abilityCode) + '\n'
                + "ai-agent");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
