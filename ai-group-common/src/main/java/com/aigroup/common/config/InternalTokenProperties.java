package com.aigroup.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai-group.internal")
public class InternalTokenProperties {

    /**
     * Shared secret for /internal/** service-to-service calls.
     */
    private String token = "ai-group-dev-internal-token-change-in-prod";
}
