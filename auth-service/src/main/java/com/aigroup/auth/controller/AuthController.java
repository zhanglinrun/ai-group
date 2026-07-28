package com.aigroup.auth.controller;

import com.aigroup.auth.dto.LoginDTO;
import com.aigroup.auth.dto.RegisterDTO;
import com.aigroup.auth.service.AuthService;
import com.aigroup.auth.vo.LoginVO;
import com.aigroup.auth.vo.RefreshTokenVO;
import com.aigroup.auth.vo.UserVO;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.model.Result;
import com.aigroup.common.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 8192;

    private final AuthService authService;
    private final JwtUtils jwtUtils;

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO loginDTO) {
        return Result.success(authService.login(loginDTO));
    }

    @PostMapping("/register")
    public Result<UserVO> register(@RequestBody RegisterDTO registerDTO) {
        return Result.success(authService.register(registerDTO));
    }

    @PostMapping("/refresh")
    public Result<RefreshTokenVO> refresh(@RequestBody Map<String, String> body) {
        return Result.success(authService.refresh(body.get("refreshToken")));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request,
                               @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null
                && authHeader.length() <= MAX_AUTHORIZATION_HEADER_LENGTH
                && authHeader.startsWith(CommonConstant.TOKEN_PREFIX)) {
            String token = authHeader.substring(CommonConstant.TOKEN_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                authService.logout(token);
            }
        }
        return Result.success();
    }

    @GetMapping("/profile")
    public Result<UserVO> profile() {
        Long userId = RequestUserContext.getUserId();
        return Result.success(authService.profile(userId));
    }
}
