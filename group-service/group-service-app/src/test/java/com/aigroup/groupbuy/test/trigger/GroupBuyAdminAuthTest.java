package com.aigroup.groupbuy.test.trigger;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.groupbuy.admin.GroupBuyAdminController;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupBuyAdminAuthTest {

    @After
    public void clearUser() {
        RequestUserContext.clear();
    }

    @Test
    public void isAdminUsesJwtRoleNotHeader() {
        GroupBuyAdminController controller = new GroupBuyAdminController();
        ReflectionTestUtils.setField(controller, "internalToken", "internal-token");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Gateway-Request", "true");
        request.addHeader("X-Internal-Token", "internal-token");
        request.addHeader("X-Role", "ADMIN");

        RequestUserContext.bind(1L, "alice", "USER");
        assertFalse(controller.isAdmin(request));

        RequestUserContext.bind(1L, "alice", "ADMIN");
        assertTrue(controller.isAdmin(request));
    }

    @Test
    public void isAdminRejectsMissingGatewayProof() {
        GroupBuyAdminController controller = new GroupBuyAdminController();
        ReflectionTestUtils.setField(controller, "internalToken", "internal-token");
        RequestUserContext.bind(1L, "alice", "ADMIN");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Token", "internal-token");
        assertFalse(controller.isAdmin(request));
    }
}
