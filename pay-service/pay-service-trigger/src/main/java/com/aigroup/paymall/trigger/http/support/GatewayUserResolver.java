package com.aigroup.paymall.trigger.http.support;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.security.InternalIdentityJwt;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves authenticated user id from the Gateway-minted JWT only.
 *
 * <p>Gateway proof (flag + internal token) is still required so a leaked JWT
 * cannot be replayed against a directly reachable Pay instance. Body
 * {@code userId} may only match the JWT subject.</p>
 */
@Component
public class GatewayUserResolver {

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    @Value("${ai-group.identity.signing-secret:}")
    private String identitySigningSecret;

    public GatewayUserResolver() {
    }

    public String resolveUserId(HttpServletRequest request, String bodyUserId) {
        if (!isGatewayRequest(request)) {
            throw new IllegalArgumentException("pay API must be accessed through gateway");
        }
        if (!isValidInternalToken(request)) {
            throw new IllegalArgumentException("pay API missing or invalid internal token");
        }
        InternalIdentityJwt.Claims claims = InternalIdentityJwt.verify(
                identitySigningSecret, request.getHeader(CommonConstant.HEADER_INTERNAL_JWT));
        if (claims == null) {
            throw new IllegalArgumentException("missing authenticated user");
        }
        String resolved = String.valueOf(claims.userId());
        if (StringUtils.isNotBlank(bodyUserId) && !resolved.equals(bodyUserId.trim())) {
            throw new IllegalArgumentException("user identity mismatch");
        }
        return resolved;
    }

    private boolean isGatewayRequest(HttpServletRequest request) {
        return request != null && "true".equalsIgnoreCase(request.getHeader(CommonConstant.HEADER_GATEWAY_REQUEST));
    }

    private boolean isValidInternalToken(HttpServletRequest request) {
        if (request == null || StringUtils.isBlank(internalToken)) {
            return false;
        }
        String provided = request.getHeader(CommonConstant.HEADER_INTERNAL_TOKEN);
        return internalToken.equals(provided);
    }
}
