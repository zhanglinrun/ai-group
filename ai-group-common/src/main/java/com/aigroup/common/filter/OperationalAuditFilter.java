package com.aigroup.common.filter;

import com.aigroup.common.context.RequestUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Emits token-free audit events for internal, actuator and Member administration operations. */
public class OperationalAuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OperationalAuditFilter.class);
    private static final int MAX_VALUE_LENGTH = 128;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || (!path.startsWith("/internal/")
                && !path.startsWith("/actuator/")
                && !path.startsWith("/api/member/admin/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            Long userId = RequestUserContext.getUserId();
            String actorType = userId == null
                    ? safe(request.getAttribute(InternalApiAuthFilter.AUDIT_ACTOR_TYPE_ATTRIBUTE))
                    : "gateway-user";
            String actorId = userId == null
                    ? safe(request.getAttribute(InternalApiAuthFilter.AUDIT_ACTOR_ID_ATTRIBUTE))
                    : String.valueOf(userId);
            log.info("security_audit action=operation actorType={} actorId={} requestId={} method={} path={} status={} durationMs={}",
                    actorType, actorId, safe(request.getHeader("X-Request-Id")), safe(request.getMethod()),
                    safe(request.getRequestURI()), response.getStatus(), (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    private static String safe(Object value) {
        if (value == null) {
            return "-";
        }
        String text = String.valueOf(value).replace('\r', '_').replace('\n', '_');
        return text.length() <= MAX_VALUE_LENGTH ? text : text.substring(0, MAX_VALUE_LENGTH);
    }
}
