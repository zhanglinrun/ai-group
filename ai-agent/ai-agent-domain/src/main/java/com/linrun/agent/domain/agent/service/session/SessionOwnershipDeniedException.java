package com.linrun.agent.domain.agent.service.session;

/**
 * 会话归属校验失败异常。
 */
public class SessionOwnershipDeniedException extends RuntimeException {

    public SessionOwnershipDeniedException(String message) {
        super(message);
    }
}
