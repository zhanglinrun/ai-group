package com.aigroup.groupbuy.config;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 内部接口鉴权过滤器；校验网关注入的 X-Internal-Token 请求头是否等于配置值 ai-group.internal.token。
 * <p>
 * 默认放行：ai-group.internal.auth-enabled 未开启（默认 false）或 token 未配置时不拦截，
 * 保证现有测试和本地不带 token 的调用（如 s-pay-mall 直连）不被破坏；
 * 开启后 /api/v1/gbm/trade/**、/api/v1/gbm/dcc/** 请求校验失败返回 403。
 */
@Component
public class InternalTokenAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** Ops-only endpoints (no service-to-service caller): always require the token when one is configured. */
    private static final String[] ALWAYS_PROTECTED_PREFIXES = {"/api/v1/gbm/dcc/"};
    /** Service-to-service endpoints: require the token only when auth is explicitly enabled (keeps dev/direct calls working). */
    private static final String[] OPT_IN_PROTECTED_PREFIXES = {"/api/v1/gbm/trade/"};

    @Value("${ai-group.internal.auth-enabled:false}")
    private boolean authEnabled;

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (requiresIntercept(request) && !isAuthorized(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean requiresIntercept(HttpServletRequest request) {
        // No token configured => local dev convenience, do not intercept.
        if (StringUtils.isBlank(internalToken)) {
            return false;
        }
        String requestPath = request.getRequestURI();
        for (String prefix : ALWAYS_PROTECTED_PREFIXES) {
            if (requestPath.startsWith(prefix)) {
                return true;
            }
        }
        if (authEnabled) {
            for (String prefix : OPT_IN_PROTECTED_PREFIXES) {
                if (requestPath.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAuthorized(HttpServletRequest request) {
        return internalToken.equals(request.getHeader(HEADER_INTERNAL_TOKEN));
    }

}
