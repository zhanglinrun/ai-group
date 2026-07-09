package com.aigroup.auth.config;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignInternalTokenConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(InternalTokenProperties properties) {
        return template -> {
            String target = template.url();
            if (target == null || target.isBlank()) {
                target = template.path();
            }
            if (target != null && target.contains("/internal/")) {
                template.header(CommonConstant.HEADER_INTERNAL_TOKEN, properties.getToken());
            }
        };
    }
}
