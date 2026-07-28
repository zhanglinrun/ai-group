package com.aigroup.common.constant;

/**
 * Unified API error codes.
 */
public enum ErrorCodeEnum {
    SUCCESS(200, "操作成功"),

    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权，请重新登录"),
    FORBIDDEN(403, "拒绝访问"),
    NOT_FOUND(404, "请求资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    CONFLICT(409, "请求冲突"),
    UNPROCESSABLE_ENTITY(422, "请求参数验证失败"),

    INTERNAL_SERVER_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),
    GATEWAY_TIMEOUT(504, "网关超时"),

    BUSINESS_ERROR(600, "业务逻辑错误"),
    PARAM_ERROR(601, "参数错误"),
    DATA_ERROR(602, "数据错误"),
    PERMISSION_ERROR(603, "权限错误"),
    TOKEN_ERROR(604, "令牌错误"),
    TOKEN_EXPIRED(605, "令牌过期"),
    USER_NOT_FOUND(606, "用户不存在"),
    USER_EXISTED(607, "用户已存在"),
    PASSWORD_ERROR(608, "密码错误"),

    QUOTA_ACCOUNT_NOT_FOUND(620, "额度账户不存在"),
    QUOTA_INSUFFICIENT(621, "配额不足"),
    FREEZE_NOT_FOUND(622, "预扣记录不存在"),

    DATABASE_ERROR(612, "数据库错误"),
    REDIS_ERROR(613, "Redis错误"),
    THIRD_PARTY_ERROR(614, "第三方服务错误"),

    SYSTEM_ERROR(900, "系统错误"),
    CONFIG_ERROR(901, "配置错误"),
    NETWORK_ERROR(902, "网络错误"),
    TIMEOUT_ERROR(903, "超时错误");

    private final Integer code;
    private final String message;

    ErrorCodeEnum(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static ErrorCodeEnum getByCode(Integer code) {
        for (ErrorCodeEnum errorCode : values()) {
            if (errorCode.code.equals(code)) {
                return errorCode;
            }
        }
        return INTERNAL_SERVER_ERROR;
    }
}
