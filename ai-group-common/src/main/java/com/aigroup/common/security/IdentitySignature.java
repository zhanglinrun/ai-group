package com.aigroup.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Shared HMAC envelope used when Gateway forwards a verified user to Python. */
public final class IdentitySignature {

    private IdentitySignature() {
    }

    public static String sign(String secret, String userId, String role,
                              String timestamp, String nonce, String path) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        // The BFF rewrites /api/bff/agent/** to the Agent's /api/** path. The
        // signature authenticates the identity envelope and nonce while routing
        // remains responsible for endpoint authorization.
        String payload = String.join(".", userId, role, timestamp, nonce);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                output.append(String.format("%02x", value));
            }
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot sign Gateway identity", exception);
        }
    }
}
