package com.aigroup.common.filter;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.context.RequestUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class GatewayUserContextFilter extends OncePerRequestFilter {

    private final InternalTokenProperties internalTokenProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            if (isGatewayVerified(request)) {
                RequestUserContext.bind(request);
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
