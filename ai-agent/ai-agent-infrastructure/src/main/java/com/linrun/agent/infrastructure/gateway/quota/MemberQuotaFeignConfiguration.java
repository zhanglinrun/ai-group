package com.linrun.agent.infrastructure.gateway.quota;

import org.springframework.beans.factory.annotation.Value;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/** Feign configuration scoped to the member quota client. */
public class MemberQuotaFeignConfiguration {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${ai-group.internal.token:}") String token) {
        return template -> template.header("X-Internal-Token", token);
    }
}
