package com.aigroup.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fail fast when production profile uses insecure default secrets.
 */
@Slf4j
@Component
public class ProductionSecurityValidator {

    private static final String DEV_JWT_SECRET = "change-me-to-a-long-random-secret";
    private static final String DEV_INTERNAL_TOKEN = "change-me-to-a-long-random-internal-token";

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    private final Environment environment;

    public ProductionSecurityValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (isLocalProfile()) {
            log.info("Local security profile detected; secret validation delegated to explicit local configuration");
            return;
        }
        if (!StringUtils.hasText(jwtSecret)
                || jwtSecret.length() < 32
                || jwtSecret.contains("change-in-prod")
                || DEV_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException("JWT secret must be configured with at least 32 random characters outside local profiles");
        }
        if (!StringUtils.hasText(internalToken)
                || internalToken.length() < 32
                || internalToken.contains("change-in-prod")
                || DEV_INTERNAL_TOKEN.equals(internalToken)) {
            throw new IllegalStateException("Internal token must be configured with at least 32 random characters outside local profiles");
        }
        log.info("Non-local security configuration validated");
    }

    private boolean isLocalProfile() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            return false;
        }
        for (String profile : profiles) {
            if (!"local".equalsIgnoreCase(profile)
                    && !"dev".equalsIgnoreCase(profile)
                    && !"test".equalsIgnoreCase(profile)) {
                return false;
            }
        }
        return true;
    }
}
