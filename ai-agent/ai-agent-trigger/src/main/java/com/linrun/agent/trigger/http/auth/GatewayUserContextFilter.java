package com.linrun.agent.trigger.http.auth;

import com.aigroup.common.constant.CommonConstant;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Binds the owner only from headers established by the Gateway/internal caller. */
public class GatewayUserContextFilter extends OncePerRequestFilter {

    private final String internalToken;

    public GatewayUserContextFilter(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (isGatewayVerified(request)) {
                String userId = request.getHeader(CommonConstant.HEADER_USER_ID);
                if (StringUtils.isNotBlank(userId)) {
                    try {
                        OwnerRequestContext.bind(Long.parseLong(userId.trim()));
                    } catch (NumberFormatException ignored) {
                        logger.warn("invalid " + CommonConstant.HEADER_USER_ID + " header");
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            OwnerRequestContext.clear();
        }
    }

    private boolean isGatewayVerified(HttpServletRequest request) {
        return "true".equalsIgnoreCase(request.getHeader(CommonConstant.HEADER_GATEWAY_REQUEST))
                && StringUtils.isNotBlank(internalToken)
                && internalToken.equals(request.getHeader(CommonConstant.HEADER_INTERNAL_TOKEN));
    }
}
