package com.aigroup.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Rejects unsafe placeholder credentials without adding a second authentication protocol. */
@Component
public class ProductionSecurityValidator {

    private final Environment environment;

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    @Value("${ai-group.identity.signing-secret:}")
    private String identitySigningSecret;

    public ProductionSecurityValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (isLocalProfile()) {
            return;
        }
        if (!StringUtils.hasText(internalToken)
                || internalToken.length() < 32
                || internalToken.contains("change-in-prod")) {
            throw new IllegalStateException("Internal token must be a random value of at least 32 characters");
        }
        if (!StringUtils.hasText(identitySigningSecret)
                || identitySigningSecret.length() < 32
                || identitySigningSecret.contains("change-in-prod")) {
            throw new IllegalStateException(
                    "Identity signing secret must be a random value of at least 32 characters");
        }
    }

    private boolean isLocalProfile() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            return false;
        }
        for (String profile : profiles) {
            if (!"local".equalsIgnoreCase(profile) && !"dev".equalsIgnoreCase(profile)
                    && !"test".equalsIgnoreCase(profile)) {
                return false;
            }
        }
        return true;
    }
}
