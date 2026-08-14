package com.aigroup.bff.config;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class FeignAuthForwardConfig {

    @Bean
    public RequestInterceptor authForwardInterceptor(InternalTokenProperties internalTokenProperties) {
        return template -> {
            HttpServletRequest request = currentRequest();
            if (request != null) {
                copyHeader(request, template, CommonConstant.HEADER_INTERNAL_JWT);
                copyHeader(request, template, CommonConstant.HEADER_USER_ID);
                copyHeader(request, template, CommonConstant.HEADER_USERNAME);
                copyHeader(request, template, CommonConstant.HEADER_ROLE);
                copyHeader(request, template, CommonConstant.HEADER_GATEWAY_REQUEST);
                copyHeader(request, template, CommonConstant.HEADER_INTERNAL_TOKEN);
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

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest();
    }

    private static void copyHeader(HttpServletRequest request, feign.RequestTemplate template, String name) {
        String value = request.getHeader(name);
        if (value != null && !value.isBlank()) {
            template.header(name, value);
        }
    }
}
