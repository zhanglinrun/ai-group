package com.aigroup.auth.controller;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthControllerAuthorizationTest {

    @AfterEach
    void clear() {
        RequestUserContext.clear();
    }

    @Test
    void profileRejectsDirectCallWithoutVerifiedIdentity() {
        assertThrows(BusinessException.class, () -> new AuthController(null).profile());
    }

    @Test
    void meRejectsDirectCallWithoutVerifiedIdentity() {
        assertThrows(BusinessException.class, () -> new AuthController(null).me());
    }
}
