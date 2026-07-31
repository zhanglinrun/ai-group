package com.aigroup.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai-group.internal")
public class InternalTokenProperties {

    /** Shared token for Gateway-to-service and explicit internal calls. */
    private String token;
}
