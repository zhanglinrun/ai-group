package com.aigroup.common.context;

import com.aigroup.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestUserContextTest {

    @AfterEach
    void clear() {
        RequestUserContext.clear();
    }

    @Test
    void requiresAUserBoundFromJwtClaims() {
        assertThrows(BusinessException.class, RequestUserContext::requireUserId);

        RequestUserContext.bind(42L, "tester", "USER");

        assertEquals(42L, RequestUserContext.requireUserId());
        assertEquals("tester", RequestUserContext.getUsername());
        assertEquals("USER", RequestUserContext.getRole());
    }
}
