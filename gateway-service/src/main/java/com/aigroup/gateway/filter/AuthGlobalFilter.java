package com.aigroup.gateway.filter;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.session.SaSession;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** Public callbacks that do not require a login session (e.g. Alipay async notify). */
    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/v1/alipay/alipay_notify_url",
            "/api/pay/alipay/notify"
    );

    /** Gateway's own actuator surface is for authenticated operations, never browser traffic. */
    private static final List<String> MANAGEMENT_PATHS = List.of("/actuator/**");

    private final InternalTokenProperties internalTokenProperties;

    @Value("${ai-group.identity.signing-secret:}")
    private String identitySigningSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (isManagementPath(path)) {
            if (!isValidInternalToken(request)) {
                log.warn("Rejected management request without valid service credential, path={}", path);
                return forbidden(exchange);
            }
            ServerHttpRequest downstream = GatewayIdentityHeaderSupport.stripUntrustedIdentity(request);
            return chain.filter(exchange.mutate().request(downstream).build());
        }

        if (isWhiteList(path)) {
            ServerHttpRequest downstream = GatewayIdentityHeaderSupport.stripUntrustedIdentity(request);
            return chain.filter(exchange.mutate().request(downstream).build());
        }

        boolean cookieToken = request.getCookies().getFirst("satoken") != null;
        String saToken = cookieToken
                ? request.getCookies().getFirst("satoken").getValue()
                : null;
        if (!StringUtils.hasText(saToken)) {
            String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (StringUtils.hasText(authorization) && authorization.startsWith(CommonConstant.TOKEN_PREFIX)) {
                saToken = authorization.substring(CommonConstant.TOKEN_PREFIX.length()).trim();
            }
        }
        if (!StringUtils.hasText(saToken) || saToken.length() > MAX_TOKEN_LENGTH) {
            return unauthorized(exchange);
        }

        String candidate = saToken;
        return Mono.fromCallable(() -> {
                    Object loginId = StpUtil.getLoginIdByToken(candidate);
                    // StpUtil.isLogin(Object) checks a login id, not a
                    // token. Resolve the token first so a browser cookie
                    // is validated against the shared Sa-Token store.
                    if (loginId == null || "0".equals(String.valueOf(loginId))) {
                        return null;
                    }
                    return Long.valueOf(String.valueOf(loginId));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(userId -> {
                    SaSession tokenSession = StpUtil.getTokenSessionByToken(candidate);
                    String username = tokenSession == null ? null
                            : stringValue(tokenSession.get("username"));
                    String role = tokenSession == null ? null
                            : stringValue(tokenSession.get("role"));
                    ServerHttpRequest downstream = GatewayIdentityHeaderSupport.withVerifiedIdentity(
                            request, userId, username, role == null ? "USER" : role,
                            internalTokenProperties.getToken(), identitySigningSecret);
                    return chain.filter(exchange.mutate().request(downstream).build());
                })
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange)))
                .onErrorResume(ex -> {
                    log.debug("Sa-Token validation failed, path={}, errorType={}",
                            path, ex.getClass().getSimpleName());
                    return unauthorized(exchange);
                });
    }

    private boolean isWhiteList(String path) {
        return WHITE_LIST.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private boolean isManagementPath(String path) {
        return MANAGEMENT_PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
