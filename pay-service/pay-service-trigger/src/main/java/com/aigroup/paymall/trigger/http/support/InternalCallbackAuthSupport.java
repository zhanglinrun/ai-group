package com.aigroup.paymall.trigger.http.support;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InternalCallbackAuthSupport {

    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    public boolean isAuthorized(HttpServletRequest request) {
        if (request == null || StringUtils.isBlank(internalToken)) {
            return false;
        }
        String provided = request.getHeader(HEADER_INTERNAL_TOKEN);
        return internalToken.equals(provided);
    }
}
