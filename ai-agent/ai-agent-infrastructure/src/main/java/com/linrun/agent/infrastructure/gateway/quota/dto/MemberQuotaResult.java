package com.linrun.agent.infrastructure.gateway.quota.dto;

import lombok.Data;

@Data
public class MemberQuotaResult<T> {

    private Integer code;
    private String message;
    private T data;
}
