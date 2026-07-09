package com.aigroup.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fail fast when production profile uses insecure default secrets.
 */
@Slf4j
@Component
@Profile("prod")
public class ProductionSecurityValidator {

    private static final String DEV_JWT_SECRET = "ai-group-dev-jwt-secret-2026-k8m3p9x2v7n4q1w6-change-in-prod";
    private static final String DEV_INTERNAL_TOKEN = "ai-group-dev-internal-token-change-in-prod";

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!StringUtils.hasText(jwtSecret)
                || jwtSecret.contains("change-in-prod")
                || DEV_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException("Production JWT secret must be configured and must not use dev defaults");
        }
        if (!StringUtils.hasText(internalToken)
                || internalToken.contains("change-in-prod")
                || DEV_INTERNAL_TOKEN.equals(internalToken)) {
            throw new IllegalStateException("Production internal token must be configured and must not use dev defaults");
        }
        log.info("Production security configuration validated");
    }
}
