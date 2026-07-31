package com.aigroup.common.context;

import com.aigroup.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestUserContextTest {

    @AfterEach
    void clear() {
        RequestUserContext.clear();
    }

    @Test
    void requiresAUserBoundByGatewayHeaders() {
        assertThrows(BusinessException.class, RequestUserContext::requireUserId);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "42");
        request.addHeader("X-Username", "tester");
        request.addHeader("X-Role", "USER");
        RequestUserContext.bind(request);

        assertEquals(42L, RequestUserContext.requireUserId());
    }
}
