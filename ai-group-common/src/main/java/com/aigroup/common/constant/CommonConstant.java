package com.aigroup.common.constant;

/**
 * Shared constants for the AI Group platform.
 */
public final class CommonConstant {

    private CommonConstant() {
    }

    public static final Integer PAGE_SIZE = 10;
    public static final Integer PAGE_NUM = 1;
    public static final Integer MAX_PAGE_SIZE = 100;

    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String TOKEN_HEADER = "Authorization";

    /** Internal identity headers injected by gateway (not trusted from clients). */
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_ROLE = "X-Role";
    /** Set by gateway after Sa-Token validation; downstream only trusts identity when present. */
    public static final String HEADER_GATEWAY_REQUEST = "X-Gateway-Request";
    /** Service-to-service internal API token. */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
    /** Gateway-minted HS256 identity JWT; not the browser Sa-Token. */
    public static final String HEADER_INTERNAL_JWT = "X-Internal-Jwt";
    public static final String EVENT_GROUP_BUY_COMPLETED = "GROUP_BUY_COMPLETED";
    public static final String EVENT_GROUP_BUY_REVOKED = "GROUP_BUY_REVOKED";

    public static final String DEFAULT_ENCODING = "UTF-8";
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
}
