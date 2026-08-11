package com.aigroup.auth.service.impl;

import com.aigroup.auth.dto.LoginDTO;
import com.aigroup.auth.dto.RegisterDTO;
import com.aigroup.auth.entity.User;
import com.aigroup.auth.mapper.UserMapper;
import com.aigroup.auth.service.AuthService;
import com.aigroup.auth.service.AuthOutboxService;
import com.aigroup.auth.vo.LoginVO;
import com.aigroup.auth.vo.UserVO;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.exception.SaTokenContextException;
import com.aigroup.common.constant.ErrorCodeEnum;
import com.aigroup.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthOutboxService authOutboxService;

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
    public void logout() {
        // HTTP requests have a Sa-Token context. Background jobs / unit tests
        // may call logout without a servlet context, so this is best effort.
        try {
            StpUtil.logout();
        } catch (SaTokenContextException ignored) {
            // no request context
        }
    }

    private LoginVO buildLoginVO(User user) {
        StpUtil.login(user.getId());
        // SaLoginModel extras are not persisted by every Sa-Token DAO mode;
        // token-session fields are explicit and therefore available to the
        // Gateway when it validates the browser cookie.
        StpUtil.getTokenSession().set("username", user.getUsername());
        StpUtil.getTokenSession().set("role", user.getRole());
        LoginVO loginVO = new LoginVO();
        loginVO.setAuthenticated(true);
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        loginVO.setUser(userVO);
        return loginVO;
    }

}
