package com.linrun.agent.trigger.http.agent;

import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Creates short-lived, owner-bound attachment access signatures without exposing the storage URL to the model. */
@Component
public class AttachmentAccessSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final String secret;

    @Autowired
    public AttachmentAccessSigner(ReactorConfig reactorConfig) {
        this(StringUtils.defaultIfBlank(reactorConfig == null ? null : reactorConfig.getReactorToolToken(),
                "researchpilot-local-attachment-signing"));
    }

    AttachmentAccessSigner(String secret) {
        this.secret = StringUtils.defaultIfBlank(secret, "researchpilot-local-attachment-signing");
    }

    public String sign(String ownerId, String sessionId, String resourceKey, long expiresAtEpochMillis) {
        return signature(payload(ownerId, sessionId, resourceKey, expiresAtEpochMillis));
    }

    public boolean verifies(String ownerId, String sessionId, String resourceKey,
                            long expiresAtEpochMillis, String suppliedSignature) {
        if (expiresAtEpochMillis <= System.currentTimeMillis() || StringUtils.isBlank(suppliedSignature)) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(ownerId, sessionId, resourceKey, expiresAtEpochMillis).getBytes(StandardCharsets.UTF_8),
                suppliedSignature.getBytes(StandardCharsets.UTF_8));
    }

    private String payload(String ownerId, String sessionId, String resourceKey, long expiresAtEpochMillis) {
        return StringUtils.defaultString(ownerId) + '\n' + StringUtils.defaultString(sessionId) + '\n'
                + StringUtils.defaultString(resourceKey) + '\n' + expiresAtEpochMillis;
    }

    private String signature(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("attachment signature unavailable", exception);
        }
    }
}
