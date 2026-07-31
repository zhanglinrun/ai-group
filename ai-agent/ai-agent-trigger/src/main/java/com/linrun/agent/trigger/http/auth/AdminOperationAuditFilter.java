package com.linrun.agent.trigger.http.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Emits a token-free audit record after every authenticated management operation. */
public class AdminOperationAuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminOperationAuditFilter.class);
    private static final int MAX_AUDIT_VALUE_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("security_audit action=management actorType={} actorId={} requestId={} method={} path={} status={} durationMs={}",
                    safe(request.getAttribute(InternalApiTokenFilter.ACTOR_TYPE_ATTRIBUTE)),
                    safe(request.getAttribute(InternalApiTokenFilter.ACTOR_ID_ATTRIBUTE)),
                    safe(request.getHeader("X-Request-Id")),
                    safe(request.getMethod()),
                    safe(request.getRequestURI()),
                    response.getStatus(),
                    (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    private String safe(Object value) {
        if (value == null) {
            return "-";
        }
        String text = String.valueOf(value).replace('\r', '_').replace('\n', '_');
        return text.length() <= MAX_AUDIT_VALUE_LENGTH ? text : text.substring(0, MAX_AUDIT_VALUE_LENGTH);
    }
}
