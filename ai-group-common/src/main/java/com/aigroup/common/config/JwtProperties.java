package com.aigroup.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT configuration loaded from environment.
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** Shared HMAC secret used by the resume-project services. */
    private String secret;

    /** Access token TTL in milliseconds (default 30 minutes). */
    private long accessExpirationMs = 30 * 60 * 1000L;

    /** Refresh token TTL in milliseconds (default 7 days). */
    private long refreshExpirationMs = 7 * 24 * 60 * 60 * 1000L;
}
