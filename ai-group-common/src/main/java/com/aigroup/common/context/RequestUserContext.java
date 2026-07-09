package com.aigroup.common.context;

import com.aigroup.common.constant.CommonConstant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

public final class RequestUserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private RequestUserContext() {
    }

    public static void bind(HttpServletRequest request) {
        String userId = request.getHeader(CommonConstant.HEADER_USER_ID);
        if (StringUtils.hasText(userId)) {
            USER_ID.set(Long.parseLong(userId));
        }
        USERNAME.set(request.getHeader(CommonConstant.HEADER_USERNAME));
        ROLE.set(request.getHeader(CommonConstant.HEADER_ROLE));
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static String getRole() {
        return ROLE.get();
    }

    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
        ROLE.remove();
    }
}
