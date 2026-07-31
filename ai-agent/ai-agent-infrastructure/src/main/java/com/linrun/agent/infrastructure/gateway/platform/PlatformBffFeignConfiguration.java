package com.linrun.agent.infrastructure.gateway.platform;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/** Client-scoped headers for the Agent's read-only BFF calls. */
public class PlatformBffFeignConfiguration {

    @Bean
    public RequestInterceptor platformBffIdentityInterceptor(
            @Value("${ai-group.internal.token:}") String token) {
        return template -> {
            template.header("X-Internal-Token", token);
            template.header("X-Gateway-Request", "true");
        };
    }
}
