package com.aigroup.bff.config;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.context.RequestUserContext;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignAuthForwardConfig {

    @Bean
    public RequestInterceptor authForwardInterceptor(InternalTokenProperties internalTokenProperties) {
        return template -> {
            Long userId = RequestUserContext.getUserId();
            if (userId != null) {
                template.header(CommonConstant.HEADER_USER_ID, String.valueOf(userId));
                template.header(CommonConstant.HEADER_GATEWAY_REQUEST, "true");
                template.header(CommonConstant.HEADER_INTERNAL_TOKEN, internalTokenProperties.getToken());
            }
            String username = RequestUserContext.getUsername();
            if (username != null) {
                template.header(CommonConstant.HEADER_USERNAME, username);
            }
            String role = RequestUserContext.getRole();
            if (role != null) {
                template.header(CommonConstant.HEADER_ROLE, role);
            }
            String target = template.url();
            if (target == null || target.isBlank()) {
                target = template.path();
            }
            if (target != null && target.contains("/internal/")) {
                template.header(CommonConstant.HEADER_INTERNAL_TOKEN, internalTokenProperties.getToken());
            }
        };
    }
}
