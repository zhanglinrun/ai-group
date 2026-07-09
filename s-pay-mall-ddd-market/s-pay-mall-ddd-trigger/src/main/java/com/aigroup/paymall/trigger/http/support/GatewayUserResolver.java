package com.aigroup.paymall.trigger.http.support;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves authenticated user id from Gateway-injected headers only.
 *
 * <p>To prevent header spoofing when services are reachable directly, identity headers are trusted
 * only when accompanied by a shared internal token injected by gateway.</p>
 */
@Component
public class GatewayUserResolver {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_GATEWAY_REQUEST = "X-Gateway-Request";
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    public GatewayUserResolver() {
    }

    public String resolveUserId(HttpServletRequest request, String bodyUserId) {
        if (!isGatewayRequest(request)) {
            throw new IllegalArgumentException("pay API must be accessed through gateway");
        }
        if (!isValidInternalToken(request)) {
            throw new IllegalArgumentException("pay API missing or invalid internal token");
        }
        String gatewayUserId = request.getHeader(HEADER_USER_ID);
        if (StringUtils.isBlank(gatewayUserId)) {
            throw new IllegalArgumentException("missing authenticated user");
        }
        String resolved = gatewayUserId.trim();
        if (StringUtils.isNotBlank(bodyUserId) && !resolved.equals(bodyUserId.trim())) {
            throw new IllegalArgumentException("user identity mismatch");
        }
        return resolved;
    }

    private boolean isGatewayRequest(HttpServletRequest request) {
        return request != null && "true".equalsIgnoreCase(request.getHeader(HEADER_GATEWAY_REQUEST));
    }

    private boolean isValidInternalToken(HttpServletRequest request) {
        if (request == null || StringUtils.isBlank(internalToken)) {
            return false;
        }
        String provided = request.getHeader(HEADER_INTERNAL_TOKEN);
        return internalToken.equals(provided);
    }
}
