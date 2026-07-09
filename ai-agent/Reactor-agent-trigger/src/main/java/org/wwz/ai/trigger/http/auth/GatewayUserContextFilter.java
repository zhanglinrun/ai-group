package org.wwz.ai.trigger.http.auth;

import com.aigroup.common.constant.CommonConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.wwz.ai.types.agent.owner.OwnerRequestContext;

import java.io.IOException;

/**
 * Reads Gateway-injected identity headers and binds ownerId (= userId).
 */
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
                    } catch (NumberFormatException e) {
                        // 畸形 userId 不绑定身份，按未登录继续处理，避免整链路 500
                        logger.warn("invalid " + CommonConstant.HEADER_USER_ID + " header, skip owner binding: " + userId);
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            OwnerRequestContext.clear();
        }
    }

    private boolean isGatewayVerified(HttpServletRequest request) {
        if (!"true".equalsIgnoreCase(request.getHeader(CommonConstant.HEADER_GATEWAY_REQUEST))) {
            return false;
        }
        String provided = request.getHeader(CommonConstant.HEADER_INTERNAL_TOKEN);
        return StringUtils.isNotBlank(internalToken) && internalToken.equals(provided);
    }
}

