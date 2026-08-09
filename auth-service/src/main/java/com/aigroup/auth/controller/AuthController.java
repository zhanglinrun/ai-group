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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import cn.dev33.satoken.stp.StpUtil;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 8192;

    private final AuthService authService;

    @Value("${sa-token.token-name:satoken}")
    private String sessionCookieName;

    @Value("${sa-token.cookie.secure:false}")
    private boolean secureCookie;

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO loginDTO, HttpServletResponse response) {
        LoginVO login = authService.login(loginDTO);
        String token = login.getAccessToken();
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

    @PostMapping("/refresh")
    public Result<RefreshTokenVO> refresh(@RequestBody Map<String, String> body) {
        return Result.success(authService.refresh(body.get("refreshToken")));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request,
                               HttpServletResponse response,
                               @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null
                && authHeader.length() <= MAX_AUTHORIZATION_HEADER_LENGTH
                && authHeader.startsWith(CommonConstant.TOKEN_PREFIX)) {
            String token = authHeader.substring(CommonConstant.TOKEN_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                authService.logout(token);
            }
        } else {
            // Browser sessions use the HttpOnly Sa-Token cookie rather than an
            // Authorization header. StpUtil resolves and invalidates that cookie.
            authService.logout(null);
        }
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
