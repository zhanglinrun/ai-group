package com.linrun.agent.infrastructure.gateway.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Typed outer Result envelope returned by bff-service. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformBffResult<T> {

    private Integer code;

    private String message;

    private T data;

    private Long timestamp;

    public PlatformBffResult(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }
}
