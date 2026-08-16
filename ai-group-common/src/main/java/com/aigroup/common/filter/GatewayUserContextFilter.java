package com.aigroup.common.filter;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.security.InternalIdentityJwt;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class GatewayUserContextFilter extends OncePerRequestFilter {

    private final InternalTokenProperties internalTokenProperties;
    private final String identitySigningSecret;

    public GatewayUserContextFilter(
            InternalTokenProperties internalTokenProperties,
            @Value("${ai-group.identity.signing-secret:}") String identitySigningSecret) {
        this.internalTokenProperties = internalTokenProperties;
        this.identitySigningSecret = identitySigningSecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            if (isGatewayVerified(request)) {
                InternalIdentityJwt.Claims claims = InternalIdentityJwt.verify(
                        identitySigningSecret, request.getHeader(CommonConstant.HEADER_INTERNAL_JWT));
                if (claims == null) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                RequestUserContext.bind(claims.userId(), claims.username(), claims.role());
            }
            filterChain.doFilter(request, response);
        } finally {
            RequestUserContext.clear();
        }
    }

    private boolean isGatewayVerified(HttpServletRequest request) {
        if (!"true".equalsIgnoreCase(request.getHeader(CommonConstant.HEADER_GATEWAY_REQUEST))) {
            return false;
        }
        String expected = internalTokenProperties.getToken();
        return expected != null && !expected.isBlank()
                && expected.equals(request.getHeader(CommonConstant.HEADER_INTERNAL_TOKEN));
    }
}
