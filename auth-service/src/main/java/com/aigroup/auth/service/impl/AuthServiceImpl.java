package com.aigroup.auth.service.impl;

import com.aigroup.auth.dto.LoginDTO;
import com.aigroup.auth.dto.RegisterDTO;
import com.aigroup.auth.entity.User;
import com.aigroup.auth.mapper.UserMapper;
import com.aigroup.auth.service.AuthService;
import com.aigroup.auth.service.AuthOutboxService;
import com.aigroup.auth.service.RefreshTokenStore;
import com.aigroup.auth.vo.LoginVO;
import com.aigroup.auth.vo.RefreshTokenVO;
import com.aigroup.auth.vo.UserVO;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.exception.SaTokenContextException;
import com.aigroup.common.config.JwtProperties;
import com.aigroup.common.constant.ErrorCodeEnum;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final UserMapper userMapper;
    private final JwtUtils jwtUtils;
    private final JwtProperties jwtProperties;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;
    private final AuthOutboxService authOutboxService;
    private final RefreshTokenStore refreshTokenStore;

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        if (!StringUtils.hasText(loginDTO.getUsername()) || !StringUtils.hasText(loginDTO.getPassword())) {
            throw new BusinessException("username and password required");
        }
        User user = userMapper.selectByUsername(loginDTO.getUsername());
        // Unified error for "unknown user" vs "wrong password" to prevent username enumeration.
        if (user == null || !passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCodeEnum.PASSWORD_ERROR, "invalid username or password");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_ERROR);
        }
        return buildLoginVO(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterDTO registerDTO) {
        if (!StringUtils.hasText(registerDTO.getUsername()) || !StringUtils.hasText(registerDTO.getPassword())) {
            throw new BusinessException("username and password required");
        }
        if (userMapper.selectByUsername(registerDTO.getUsername()) != null) {
            throw new BusinessException(ErrorCodeEnum.USER_EXISTED);
        }
        User user = new User();
        user.setUsername(registerDTO.getUsername());
        user.setPassword(passwordEncoder.encode(registerDTO.getPassword()));
        user.setEmail(registerDTO.getEmail());
        user.setPhone(registerDTO.getPhone());
        user.setRole("USER");
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);

        // Account creation crosses the Auth/Member database boundary.  The
        // registration event is written to Auth's outbox in this transaction;
        // Member consumes it asynchronously and INSERT IGNORE makes delivery
        // retries idempotent.  Auth therefore never calls Member directly.
        authOutboxService.enqueueUserRegistered(user);

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public UserVO profile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND);
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public RefreshTokenVO refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            if (!StpUtil.isLogin()) {
                throw new BusinessException(ErrorCodeEnum.TOKEN_ERROR);
            }
            RefreshTokenVO session = new RefreshTokenVO();
            session.setAccessToken(StpUtil.getTokenValue());
            session.setRefreshToken("");
            return session;
        }
        Claims refreshClaims = jwtUtils.parseRefreshToken(refreshToken);
        Long userId = jwtUtils.getUserId(refreshClaims);
        String jti = jwtUtils.getJti(refreshClaims);
        if (userId == null || !StringUtils.hasText(jti)) {
            throw new BusinessException(ErrorCodeEnum.TOKEN_ERROR);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_ERROR);
        }
        // Atomically validate + revoke the presented refresh token. A replayed token
        // (already rotated) fails here because the first refresh consumed it.
        if (!refreshTokenStore.consume(userId, jti)) {
            throw new BusinessException(ErrorCodeEnum.TOKEN_ERROR, "refresh token revoked or expired");
        }

        String nextJti = UUID.randomUUID().toString().replace("-", "");
        String nextRefresh = jwtUtils.generateRefreshToken(userId, nextJti);
        refreshTokenStore.store(userId, nextJti, Duration.ofMillis(jwtProperties.getRefreshExpirationMs()));

        String access = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());
        RefreshTokenVO vo = new RefreshTokenVO();
        vo.setAccessToken(access);
        vo.setRefreshToken(nextRefresh);
        return vo;
    }

    @Override
    public void logout(String accessToken) {
        // HTTP requests have a Sa-Token context.  The token blacklist path is
        // also used by background jobs and unit tests, where no servlet
        // context exists, so invalidating the optional session is best effort.
        try {
            StpUtil.logout();
        } catch (SaTokenContextException ignored) {
            // no request context; continue with explicit token revocation
        }
        if (!StringUtils.hasText(accessToken)) {
            return;
        }
        try {
            Claims claims = jwtUtils.parseAccessToken(accessToken);
            Long userId = jwtUtils.getUserId(claims);
            long ttlMs = jwtUtils.remainingTtlMillis(claims, jwtProperties.getAccessExpirationMs());
            stringRedisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + jwtUtils.blacklistKey(accessToken),
                    "1",
                    Duration.ofMillis(ttlMs)
            );
            if (userId != null) {
                refreshTokenStore.revokeAllForUser(userId);
            }
        } catch (Exception ignore) {
            // Invalid, expired or non-access tokens are not persisted or revoked.
        }
    }

    private LoginVO buildLoginVO(User user) {
        StpUtil.login(user.getId());
        // SaLoginModel extras are not persisted by every Sa-Token DAO mode;
        // token-session fields are explicit and therefore available to the
        // Gateway when it validates the browser cookie.
        StpUtil.getTokenSession().set("username", user.getUsername());
        StpUtil.getTokenSession().set("role", user.getRole());
        String saToken = StpUtil.getTokenValue();
        LoginVO loginVO = new LoginVO();
        loginVO.setAccessToken(saToken);
        loginVO.setRefreshToken("");
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        loginVO.setUser(userVO);
        return loginVO;
    }

}
