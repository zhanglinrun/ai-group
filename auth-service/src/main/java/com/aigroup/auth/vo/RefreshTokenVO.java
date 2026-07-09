package com.aigroup.auth.vo;

import lombok.Data;

@Data
public class RefreshTokenVO {
    private String accessToken;
    private String refreshToken;
}

