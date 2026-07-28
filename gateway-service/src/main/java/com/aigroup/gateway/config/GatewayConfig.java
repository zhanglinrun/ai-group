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

    @Value("${gateway.route.auth-uri:lb://auth}")
    private String authUri;

    @Value("${gateway.route.member-uri:lb://member}")
    private String memberUri;

    @Value("${gateway.route.bff-uri:lb://bff}")
    private String bffUri;

    @Value("${gateway.route.pay-uri:lb://pay}")
    private String payUri;

    @Value("${gateway.route.group-uri:lb://group}")
    private String groupUri;

    @Value("${gateway.route.agent-uri:lb://agent}")
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
                .route("agent-api", r -> r.path("/api/agent/**", "/api/v1/agent/**")
                        .filters(f -> f
                                .setResponseHeader("X-Accel-Buffering", "no")
                                .dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(agentUri))
                // 运营端模型 Key 管理：agent 的 /api/v1/admin/**（agent 侧过滤器要求经网关请求必须 ADMIN 角色）
                .route("agent-admin", r -> r.path("/api/v1/admin/**")
                        .filters(f -> f.dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(agentUri))
                .route("agent-web", r -> r.path("/web/**")
                        .filters(f -> f
                                .setResponseHeader("X-Accel-Buffering", "no")
                                .dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(agentUri))
                .route("agent-data", r -> r.path("/data/**")
                        .filters(f -> f
                                .setResponseHeader("X-Accel-Buffering", "no")
                                .dedupeResponseHeader(CORS_RESPONSE_HEADERS, CORS_DEDUPE_STRATEGY))
                        .uri(agentUri))
                .build();
    }
}
