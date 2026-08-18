package com.aigroup.groupbuy.test.trigger;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.groupbuy.admin.GroupBuyAdminController;
import com.aigroup.groupbuy.domain.activity.service.discount.IDiscountCalculateService;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyDiscount;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    public void normalizeMarketPlanAllowsMmj() {
        GroupBuyAdminController controller = new GroupBuyAdminController();
        assertEquals("MMJ", ReflectionTestUtils.invokeMethod(controller, "normalizeMarketPlan", "mmj"));
    }

    @Test
    public void resolveGroupPayPriceDelegatesToDiscountServiceIncludingMmj() {
        GroupBuyAdminController controller = new GroupBuyAdminController();
        IDiscountCalculateService mmj = mock(IDiscountCalculateService.class);
        when(mmj.calculate(eq("admin"), eq(new BigDecimal("100.00")), any()))
                .thenReturn(new BigDecimal("80.00"));
        ReflectionTestUtils.setField(controller, "discountCalculateServiceMap", Map.of("MMJ", mmj));

        GroupBuyDiscount discount = GroupBuyDiscount.builder()
                .marketPlan("MMJ")
                .marketExpr("100,10,-1")
                .discountType(0)
                .build();
        Object price = ReflectionTestUtils.invokeMethod(
                controller, "resolveGroupPayPrice", new BigDecimal("100.00"), discount);

        assertEquals(new BigDecimal("80.00"), price);
        verify(mmj).calculate(eq("admin"), eq(new BigDecimal("100.00")), any());
    }
}
