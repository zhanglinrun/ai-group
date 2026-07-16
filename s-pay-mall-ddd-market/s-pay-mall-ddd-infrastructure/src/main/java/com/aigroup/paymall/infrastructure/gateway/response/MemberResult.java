package com.aigroup.paymall.infrastructure.gateway.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberResult<T> {
    private Integer code;
    private String message;
    private T data;
}
