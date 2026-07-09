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
    public static final String REFRESH_TOKEN_PREFIX = "Refresh ";
    public static final String TOKEN_HEADER = "Authorization";
    public static final String TOKEN_CLAIM_USER_ID = "userId";
    public static final String TOKEN_CLAIM_USERNAME = "username";
    public static final String TOKEN_CLAIM_ROLE = "role";
    public static final String TOKEN_CLAIM_JTI = "jti";
    /** Token type claim: distinguishes access tokens from refresh tokens. */
    public static final String TOKEN_CLAIM_TYPE = "typ";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /** Internal identity headers injected by gateway (not trusted from clients). */
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_ROLE = "X-Role";
    /** Set by gateway after JWT validation; downstream only trusts identity when present. */
    public static final String HEADER_GATEWAY_REQUEST = "X-Gateway-Request";
    /** Service-to-service internal API token. */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    public static final String EVENT_GROUP_BUY_COMPLETED = "GROUP_BUY_COMPLETED";
    public static final String EVENT_GROUP_BUY_REVOKED = "GROUP_BUY_REVOKED";

    public static final String DEFAULT_ENCODING = "UTF-8";
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
}
