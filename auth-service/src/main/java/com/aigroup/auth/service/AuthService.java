package com.aigroup.auth.service;

import com.aigroup.auth.dto.LoginDTO;
import com.aigroup.auth.dto.RegisterDTO;
import com.aigroup.auth.vo.LoginVO;
import com.aigroup.auth.vo.RefreshTokenVO;
import com.aigroup.auth.vo.UserVO;

public interface AuthService {

    LoginVO login(LoginDTO loginDTO);

    UserVO register(RegisterDTO registerDTO);

    UserVO profile(Long userId);

    RefreshTokenVO refresh(String refreshToken);

    void logout(String accessToken);
}
