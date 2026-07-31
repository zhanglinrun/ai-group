package com.aigroup.common.filter;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Protects internal and management endpoints with the configured service token. */
@Component
@RequiredArgsConstructor
public class InternalApiAuthFilter extends OncePerRequestFilter {

    public static final String AUDIT_ACTOR_TYPE_ATTRIBUTE = InternalApiAuthFilter.class.getName() + ".actorType";
    public static final String AUDIT_ACTOR_ID_ATTRIBUTE = InternalApiAuthFilter.class.getName() + ".actorId";

    private final InternalTokenProperties internalTokenProperties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || (!path.startsWith("/internal/") && !path.startsWith("/actuator/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String configured = internalTokenProperties.getToken();
        String provided = request.getHeader(CommonConstant.HEADER_INTERNAL_TOKEN);
        if (!StringUtils.hasText(configured) || !configured.equals(provided)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return;
        }
        request.setAttribute(AUDIT_ACTOR_TYPE_ATTRIBUTE, "internal-token");
        request.setAttribute(AUDIT_ACTOR_ID_ATTRIBUTE, "platform");
        filterChain.doFilter(request, response);
    }
}
