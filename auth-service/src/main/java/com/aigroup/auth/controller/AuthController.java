package com.aigroup.auth.controller;

import com.aigroup.auth.dto.LoginDTO;
import com.aigroup.auth.dto.RegisterDTO;
import com.aigroup.auth.service.AuthService;
import com.aigroup.auth.vo.LoginVO;
import com.aigroup.auth.vo.UserVO;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.model.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import cn.dev33.satoken.stp.StpUtil;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Value("${sa-token.token-name:satoken}")
    private String sessionCookieName;

    @Value("${sa-token.cookie.secure:false}")
    private boolean secureCookie;

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO loginDTO, HttpServletResponse response) {
        LoginVO login = authService.login(loginDTO);
        String token = StpUtil.getTokenValue();
        if (token != null && !token.isBlank()) {
            ResponseCookie cookie = ResponseCookie.from(sessionCookieName, token)
                    .httpOnly(true)
                    .secure(secureCookie)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(30 * 24 * 60 * 60L)
                    .build();
            response.addHeader("Set-Cookie", cookie.toString());
        }
        return Result.success(login);
    }

    @PostMapping("/register")
    public Result<UserVO> register(@RequestBody RegisterDTO registerDTO) {
        return Result.success(authService.register(registerDTO));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletResponse response) {
        authService.logout();
        ResponseCookie cleared = ResponseCookie.from(sessionCookieName, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cleared.toString());
        return Result.success();
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        if (!StpUtil.isLogin()) {
            throw new com.aigroup.common.exception.BusinessException("未登录");
        }
        return Result.success(authService.profile(Long.parseLong(String.valueOf(StpUtil.getLoginId()))));
    }

    @GetMapping("/profile")
    public Result<UserVO> profile() {
        Long userId = RequestUserContext.requireUserId();
        return Result.success(authService.profile(userId));
    }
}
