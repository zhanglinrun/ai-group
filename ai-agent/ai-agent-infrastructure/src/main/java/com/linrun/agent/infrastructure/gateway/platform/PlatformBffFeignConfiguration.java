package com.linrun.agent.infrastructure.gateway.platform;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/** Client-scoped trusted service identity for the platform BFF. */
public class PlatformBffFeignConfiguration {

    static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
    static final String HEADER_GATEWAY_REQUEST = "X-Gateway-Request";

    @Bean
    public RequestInterceptor platformBffIdentityInterceptor(
            @Value("${ai-group.internal.token:change-me-to-a-long-random-internal-token}") String token) {
        return template -> {
            template.header(HEADER_INTERNAL_TOKEN, token);
            // GatewayUserContextFilter accepts identity only when this marker is
            // authenticated by the matching internal token.
            template.header(HEADER_GATEWAY_REQUEST, "true");
        };
    }
}
