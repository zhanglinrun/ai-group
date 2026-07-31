package com.linrun.agent.trigger.http.auth;

import com.aigroup.common.constant.CommonConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Protects Agent management and data endpoints with the configured internal token. */
public class InternalApiTokenFilter extends OncePerRequestFilter {

    public static final String ACTOR_TYPE_ATTRIBUTE = InternalApiTokenFilter.class.getName() + ".actorType";
    public static final String ACTOR_ID_ATTRIBUTE = InternalApiTokenFilter.class.getName() + ".actorId";

    private final String internalToken;

    public InternalApiTokenFilter(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (StringUtils.isBlank(internalToken)
                || !internalToken.equals(request.getHeader(CommonConstant.HEADER_INTERNAL_TOKEN))) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return;
        }
        if ("true".equalsIgnoreCase(request.getHeader(CommonConstant.HEADER_GATEWAY_REQUEST))
                && !"ADMIN".equalsIgnoreCase(request.getHeader(CommonConstant.HEADER_ROLE))) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return;
        }
        request.setAttribute(ACTOR_TYPE_ATTRIBUTE, "internal-token");
        request.setAttribute(ACTOR_ID_ATTRIBUTE, "platform");
        filterChain.doFilter(request, response);
    }
}
