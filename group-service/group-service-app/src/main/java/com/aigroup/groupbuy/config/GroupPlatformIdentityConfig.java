package com.aigroup.groupbuy.config;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.filter.GatewayUserContextFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires platform JWT identity without scanning the rest of {@code ai-group-common}.
 * Full-package scan would pull Redis auto-config and a foreign exception handler.
 */
@Configuration
@Import({InternalTokenProperties.class, GatewayUserContextFilter.class})
public class GroupPlatformIdentityConfig {
}
