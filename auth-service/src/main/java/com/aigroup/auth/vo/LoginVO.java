package com.aigroup.auth.vo;

import lombok.Data;

@Data
public class LoginVO {
    private boolean authenticated;
    private UserVO user;
}
