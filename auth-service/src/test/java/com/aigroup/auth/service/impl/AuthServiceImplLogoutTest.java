package com.aigroup.auth.service.impl;

import com.aigroup.auth.mapper.UserMapper;
import com.aigroup.auth.service.AuthOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class AuthServiceImplLogoutTest {

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                mock(UserMapper.class),
                new BCryptPasswordEncoder(),
                mock(AuthOutboxService.class)
        );
    }

    @Test
    void logoutWithoutServletContextDoesNotThrow() {
        assertDoesNotThrow(() -> authService.logout());
    }
}
