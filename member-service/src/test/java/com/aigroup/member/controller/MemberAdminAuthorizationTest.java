package com.aigroup.member.controller;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.member.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemberAdminAuthorizationTest {

    private final MemberAdminController controller = new MemberAdminController(
            Mockito.mock(MemberService.class), Mockito.mock(com.aigroup.member.mapper.BenefitGrantEventMapper.class),
            Mockito.mock(com.aigroup.member.mapper.ProductSkuMapper.class));

    @AfterEach
    void clearContext() {
        RequestUserContext.clear();
    }

    @Test
    void nonAdminRoleIsRejected() {
        bind("USER");

        assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(controller, "requireAdmin"));
    }

    @Test
    void adminRoleIsAllowed() {
        bind("ADMIN");

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(controller, "requireAdmin"));
    }

    private void bind(String role) {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-User-Id", "1");
        request.addHeader("X-Username", "admin");
        request.addHeader("X-Role", role);
        RequestUserContext.bind(request);
    }
}
