package com.aigroup.bff.controller;

import com.aigroup.bff.client.GroupFeignClient;
import com.aigroup.bff.client.MemberFeignClient;
import com.aigroup.bff.client.PayFeignClient;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.model.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BffControllerDisplayStatusTest {

    @Mock
    private MemberFeignClient memberFeignClient;
    @Mock
    private GroupFeignClient groupFeignClient;
    @Mock
    private PayFeignClient payFeignClient;

    private BffController controller;

    @BeforeEach
    void setUp() {
        controller = new BffController(memberFeignClient, groupFeignClient, payFeignClient);
        ReflectionTestUtils.setField(controller, "groupSource", "s01");
        ReflectionTestUtils.setField(controller, "groupChannel", "c01");
        ReflectionTestUtils.setField(controller, "defaultGoodsId", "9890001");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CommonConstant.HEADER_USER_ID)).thenReturn("1001");
        RequestUserContext.bind(request);
    }

    @AfterEach
    void tearDown() {
        RequestUserContext.clear();
    }

    @Test
    void marketOrder_staysGroupFormedWhenBenefitPending() {
        when(payFeignClient.queryUserOrderList(any())).thenReturn(payResponse("MARKET", "order-1"));
        when(memberFeignClient.benefitStatus("order-1")).thenReturn(Result.success(Map.of("status", "PENDING")));

        List<Map<String, Object>> orders = (List<Map<String, Object>>) controller.orders().getData().get("items");

        assertEquals("GROUP_FORMED", orders.get(0).get("displayStatus"));
    }

    @Test
    void marketOrder_becomesBenefitGrantedWhenMemberGranted() {
        when(payFeignClient.queryUserOrderList(any())).thenReturn(payResponse("MARKET", "order-2"));
        when(memberFeignClient.benefitStatus("order-2")).thenReturn(Result.success(Map.of("status", "GRANTED")));

        List<Map<String, Object>> orders = (List<Map<String, Object>>) controller.orders().getData().get("items");

        assertEquals("BENEFIT_GRANTED", orders.get(0).get("displayStatus"));
    }

    @Test
    void marketOrder_becomesClosedWhenBenefitRevoked() {
        when(payFeignClient.queryUserOrderList(any())).thenReturn(payResponse("MARKET", "order-3"));
        when(memberFeignClient.benefitStatus("order-3")).thenReturn(Result.success(Map.of("status", "REVOKED")));

        List<Map<String, Object>> orders = (List<Map<String, Object>>) controller.orders().getData().get("items");

        assertEquals("CLOSED", orders.get(0).get("displayStatus"));
    }

    private Map<String, Object> payResponse(String status, String orderId) {
        Map<String, Object> order = new HashMap<>();
        order.put("orderId", orderId);
        order.put("status", status);
        order.put("marketType", 1);
        order.put("productName", "Pro Monthly");
        order.put("payAmount", 9.9);
        order.put("payTime", "2026-07-07T10:00:00");

        Map<String, Object> data = new HashMap<>();
        data.put("orderList", List.of(order));

        Map<String, Object> response = new HashMap<>();
        response.put("data", data);
        return response;
    }
}
