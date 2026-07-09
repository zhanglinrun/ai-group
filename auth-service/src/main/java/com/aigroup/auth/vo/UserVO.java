package com.aigroup.auth.vo;

import lombok.Data;

@Data
public class UserVO {
    private Long id;
    private String username;
    private String email;
    private String phone;
    private String role;
}
