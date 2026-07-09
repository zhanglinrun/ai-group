package org.wwz.ai.application.agent.quota;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignInternalTokenConfig {

    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${ai-group.internal.token:ai-group-dev-internal-token-change-in-prod}") String token) {
        return template -> {
            String target = template.url();
            if (target == null || target.isBlank()) {
                target = template.path();
            }
            if (target != null && target.contains("/internal/")) {
                template.header(HEADER_INTERNAL_TOKEN, token);
            }
        };
    }
}
