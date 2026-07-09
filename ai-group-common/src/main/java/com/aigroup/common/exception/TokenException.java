package com.aigroup.common.exception;

import com.aigroup.common.constant.ErrorCodeEnum;
import lombok.Getter;

/**
 * Authentication token exception.
 */
@Getter
public class TokenException extends RuntimeException {

    private final Integer code;
    private final String message;

    public TokenException(String message) {
        super(message);
        this.code = ErrorCodeEnum.UNAUTHORIZED.getCode();
        this.message = message;
    }

    public TokenException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public TokenException(ErrorCodeEnum errorCodeEnum) {
        super(errorCodeEnum.getMessage());
        this.code = errorCodeEnum.getCode();
        this.message = errorCodeEnum.getMessage();
    }
}
