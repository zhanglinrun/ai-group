package com.aigroup.common.context;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/** Request-scoped user context populated by the Gateway. */
public final class RequestUserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private RequestUserContext() {
    }

    public static void bind(HttpServletRequest request) {
        String userId = request.getHeader(CommonConstant.HEADER_USER_ID);
        if (StringUtils.hasText(userId)) {
            try {
                USER_ID.set(Long.parseLong(userId));
            } catch (NumberFormatException ignored) {
                // A malformed Gateway header is treated as an unauthenticated request.
            }
        }
        USERNAME.set(request.getHeader(CommonConstant.HEADER_USERNAME));
        ROLE.set(request.getHeader(CommonConstant.HEADER_ROLE));
    }

    public static void bind(long userId, String username, String role) {
        USER_ID.set(userId);
        USERNAME.set(username);
        ROLE.set(role);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static Long requireUserId() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        return userId;
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
