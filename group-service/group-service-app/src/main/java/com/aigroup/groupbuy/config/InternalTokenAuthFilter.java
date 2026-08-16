package com.aigroup.groupbuy.config;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 内部接口鉴权过滤器；校验 X-Internal-Token 是否等于 ai-group.internal.token。
 * <p>
 * trade 路径默认受保护（auth-enabled=true）；auth 开启但 token 未配置时启动失败（fail-closed）。
 * 临时回滚可设 AI_GROUP_INTERNAL_AUTH_ENABLED=false。
 */
@Component
public class InternalTokenAuthFilter extends OncePerRequestFilter implements InitializingBean {

    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** Ops-only endpoints always require the token. */
    private static final String[] ALWAYS_PROTECTED_PREFIXES = {"/api/v1/gbm/dcc/"};
    /** Service-to-service and user-facing endpoints: protected when auth is enabled. */
    private static final String[] OPT_IN_PROTECTED_PREFIXES = {
            "/api/v1/gbm/trade/",
            "/api/v1/gbm/index/",
            "/api/group/"
    };

    @Value("${ai-group.internal.auth-enabled:true}")
    private boolean authEnabled;

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    @Override
    public void afterPropertiesSet() {
        if (StringUtils.isBlank(internalToken)) {
            throw new IllegalStateException(
                    "ai-group.internal.token is blank; set AI_GROUP_INTERNAL_TOKEN because DCC endpoints are always protected");
        }
    }

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
        if (StringUtils.isBlank(internalToken)) {
            return false;
        }
        return internalToken.equals(request.getHeader(HEADER_INTERNAL_TOKEN));
    }
}
