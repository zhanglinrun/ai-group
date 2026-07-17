package com.linrun.agent.infrastructure.gateway.quota;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/** Feign configuration scoped to the member quota client. */
public class MemberQuotaFeignConfiguration {

    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${ai-group.internal.token:change-me-to-a-long-random-internal-token}") String token) {
        return template -> template.header(HEADER_INTERNAL_TOKEN, token);
    }
}
