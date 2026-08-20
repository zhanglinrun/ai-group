package com.aigroup.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.time.Duration;

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

    @Value("${gateway.route.pay-uri:lb://pay-service}")
    private String payUri;

    @Value("${gateway.route.group-uri:lb://group-service}")
    private String groupUri;

    @Value("${gateway.route.agent-uri:lb://agent-service}")
    private String agentUri;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("auth", r -> r.path("/api/auth/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(authUri))
                .route("member", r -> r.path("/api/member/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(memberUri))
                .route("pay-v1", r -> r.path("/api/v1/alipay/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(payUri))
                .route("pay", r -> r.path("/api/pay/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(payUri))
                .route("group", r -> r.path("/api/group/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(groupUri))
                .route("agent-sse", r -> r.order(-10)
                        .path("/api/runs/*/events")
                        .and()
                        .method(HttpMethod.GET)
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .metadata("connect-timeout", 2000)
                        .metadata("response-timeout", Duration.ofMinutes(30))
                        .uri(agentUri))
                .route("agent-json", r -> r.order(-5)
                        .path(
                                "/api/runs/**",
                                "/api/watchlist/**",
                                "/api/skill-candidates/**",
                                "/api/demo-fixtures/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .metadata("connect-timeout", 2000)
                        .metadata("response-timeout", Duration.ofSeconds(45))
                        .uri(agentUri))
                .build();
    }
}
