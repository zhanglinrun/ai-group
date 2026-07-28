package com.aigroup.gateway.filter;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** Public callbacks that do not use JWT (e.g. Alipay async notify). */
    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/v1/alipay/alipay_notify_url",
            "/web/health",
            "/actuator/**"
    );

    /**
     * Service-to-service callbacks authenticated by shared internal token.
     * group -> pay must either hit pay directly (recommended) or pass through gateway with X-Internal-Token.
     */
    private static final List<String> INTERNAL_CALLBACK_PATHS = List.of(
            "/api/v1/alipay/group_buy_notify",
            "/api/v1/alipay/active_pay_notify"
    );

    private final JwtUtils jwtUtils;
    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;
    private final InternalTokenProperties internalTokenProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (isInternalCallback(path)) {
            if (!isValidInternalToken(request)) {
                log.warn("Rejected internal callback without valid token, path={}", path);
                return forbidden(exchange);
            }
            ServerHttpRequest downstream = GatewayIdentityHeaderSupport.withInternalToken(
                    request, internalTokenProperties.getToken());
            return chain.filter(exchange.mutate().request(downstream).build());
        }

        if (isWhiteList(path)) {
            ServerHttpRequest downstream = GatewayIdentityHeaderSupport.stripUntrustedIdentity(request);
            return chain.filter(exchange.mutate().request(downstream).build());
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(CommonConstant.TOKEN_PREFIX)) {
            return unauthorized(exchange);
        }

        String token = authHeader.substring(CommonConstant.TOKEN_PREFIX.length()).trim();
        if (token.isEmpty() || token.length() > JwtUtils.MAX_TOKEN_LENGTH) {
            return unauthorized(exchange);
        }
        Claims claims;
        try {
            claims = jwtUtils.parseAccessToken(token);
        } catch (Exception ex) {
            return unauthorized(exchange);
        }
        if (claims == null) {
            return unauthorized(exchange);
        }

        return reactiveStringRedisTemplate.hasKey(BLACKLIST_PREFIX + jwtUtils.blacklistKey(token))
                .defaultIfEmpty(false)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        return unauthorized(exchange);
                    }
                    try {
                        Long userId = claims.get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class);
                        if (userId == null) {
                            log.warn("JWT missing userId claim, path={}", path);
                            return unauthorized(exchange);
                        }
                        String username = claims.get(CommonConstant.TOKEN_CLAIM_USERNAME, String.class);
                        String role = claims.get(CommonConstant.TOKEN_CLAIM_ROLE, String.class);
                        ServerHttpRequest downstream = GatewayIdentityHeaderSupport.withVerifiedIdentity(
                                request, userId, username, role, internalTokenProperties.getToken());
                        if (log.isDebugEnabled()) {
                            log.debug("Forwarding verified identity userId={} path={}", userId, path);
                        }
                        return chain.filter(exchange.mutate().request(downstream).build());
                    } catch (Exception ex) {
                        log.warn("JWT parse failed for path {}", path, ex);
                        return unauthorized(exchange);
                    }
                });
    }

    private boolean isWhiteList(String path) {
        return WHITE_LIST.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private boolean isInternalCallback(String path) {
        return INTERNAL_CALLBACK_PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private boolean isValidInternalToken(ServerHttpRequest request) {
        String configured = internalTokenProperties.getToken();
        String provided = request.getHeaders().getFirst(CommonConstant.HEADER_INTERNAL_TOKEN);
        return StringUtils.hasText(configured) && configured.equals(provided);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
