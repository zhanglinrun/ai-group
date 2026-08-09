package com.aigroup.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    static final String CORS_RESPONSE_HEADERS = String.join(" ",
            "Access-Control-Allow-Origin",
            "Access-Control-Allow-Credentials",
            "Access-Control-Allow-Headers",
            "Access-Control-Allow-Methods",
            "Access-Control-Expose-Headers");
    static final String CORS_DEDUPE_STRATEGY = "RETAIN_FIRST";

    @Value("${gateway.route.auth-uri:lb://auth-service}")
    private String authUri;

    @Value("${gateway.route.member-uri:lb://member-service}")
    private String memberUri;

    @Value("${gateway.route.bff-uri:lb://bff-service}")
    private String bffUri;

    @Value("${gateway.route.pay-uri:lb://pay-service}")
    private String payUri;

    @Value("${gateway.route.group-uri:lb://group-service}")
    private String groupUri;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("auth", r -> r.path("/api/auth/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(authUri))
                .route("member", r -> r.path("/api/member/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(memberUri))
                .route("bff", r -> r.path("/api/bff/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(bffUri))
                .route("pay-v1", r -> r.path("/api/v1/alipay/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(payUri))
                .route("pay", r -> r.path("/api/pay/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(payUri))
                .route("group", r -> r.path("/api/group/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(groupUri))
                .build();
    }
}
