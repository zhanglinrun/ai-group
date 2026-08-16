package com.aigroup.groupbuy.trigger.http.support;

import com.aigroup.common.context.RequestUserContext;
import org.apache.commons.lang3.StringUtils;

/**
 * Resolves the caller from the Gateway JWT bound by {@code GatewayUserContextFilter}.
 * Body {@code userId} may only match; it is never the identity source.
 */
public final class GatewayUserBinder {

    private GatewayUserBinder() {
    }

    public static String requireUserId(String bodyUserId) {
        Long bound = RequestUserContext.getUserId();
        if (bound == null) {
            throw new IllegalStateException("missing authenticated user");
        }
        String resolved = String.valueOf(bound);
        if (StringUtils.isNotBlank(bodyUserId) && !resolved.equals(bodyUserId.trim())) {
            throw new IllegalArgumentException("user identity mismatch");
        }
        return resolved;
    }
}
