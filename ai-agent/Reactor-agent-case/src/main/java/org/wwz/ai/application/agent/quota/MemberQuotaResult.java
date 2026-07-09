package org.wwz.ai.application.agent.quota;

import lombok.Data;

@Data
public class MemberQuotaResult<T> {

    private Integer code;
    private String message;
    private T data;
}
