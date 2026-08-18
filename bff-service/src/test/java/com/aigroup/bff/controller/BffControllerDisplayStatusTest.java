package com.aigroup.bff.controller;

import com.aigroup.bff.client.GroupFeignClient;
import com.aigroup.bff.client.MemberFeignClient;
import com.aigroup.bff.client.PayFeignClient;
import com.aigroup.bff.support.GroupMarketQueryCoordinator;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.model.Result;
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
        controller = new BffController(memberFeignClient, groupFeignClient, payFeignClient,
                new GroupMarketQueryCoordinator(0L));
        ReflectionTestUtils.setField(controller, "groupSource", "s01");
        ReflectionTestUtils.setField(controller, "groupChannel", "c01");
        ReflectionTestUtils.setField(controller, "defaultGoodsId", "9890002");

        RequestUserContext.bind(1001L, "tester", "USER");
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
    void dealDoneGroupOrder_staysBenefitGrantedAfterFulfillmentProjection() {
        when(payFeignClient.queryUserOrderList(any())).thenReturn(payResponse("DEAL_DONE", "order-2b"));
        when(memberFeignClient.benefitStatus("order-2b")).thenReturn(Result.success(Map.of("status", "GRANTED")));

        List<Map<String, Object>> orders = (List<Map<String, Object>>) controller.orders().getData().get("items");

        assertEquals("BENEFIT_GRANTED", orders.get(0).get("displayStatus"));
        assertEquals("formed", orders.get(0).get("groupStatus"));
    }

    @Test
    void dealDoneGroupOrder_keepsStablePayStatusWhenMemberIsTemporarilyUnavailable() {
        when(payFeignClient.queryUserOrderList(any())).thenReturn(payResponse("DEAL_DONE", "order-2c"));
        when(memberFeignClient.benefitStatus("order-2c")).thenThrow(new IllegalStateException("timeout"));

        List<Map<String, Object>> orders = (List<Map<String, Object>>) controller.orders().getData().get("items");

        assertEquals("GROUP_FORMED", orders.get(0).get("displayStatus"));
    }

    @Test
    void marketOrder_becomesClosedWhenBenefitRevoked() {
        when(payFeignClient.queryUserOrderList(any())).thenReturn(payResponse("MARKET", "order-3"));
        when(memberFeignClient.benefitStatus("order-3")).thenReturn(Result.success(Map.of("status", "REVOKED")));

        List<Map<String, Object>> orders = (List<Map<String, Object>>) controller.orders().getData().get("items");

        assertEquals("CLOSED", orders.get(0).get("displayStatus"));
    }

    @Test
    void marketOrder_keepsBenefitGrantedWhenMemberReportsGrantedAfterRejectedRevoke() {
        when(payFeignClient.queryUserOrderList(any())).thenReturn(payResponse("MARKET", "order-4"));
        when(memberFeignClient.benefitStatus("order-4")).thenReturn(Result.success(Map.of("status", "GRANTED")));

        List<Map<String, Object>> orders = (List<Map<String, Object>>) controller.orders().getData().get("items");

        assertEquals("BENEFIT_GRANTED", orders.get(0).get("displayStatus"));
    }

    @Test
    void accountSummary_includesAuthenticatedUsersQuotaLedger() {
        when(memberFeignClient.summary()).thenReturn(Result.success(new HashMap<>(Map.of(
                "availableQuota", 5_000_000L,
                "freeQuotaBalance", 5_000_000L,
                "paidQuotaBalance", 0L,
                "frozenBalance", 0L
        ))));
        when(memberFeignClient.quotaLedger()).thenReturn(Result.success(List.of(Map.of(
                "id", 1L,
                "type", "MONTHLY_GRANT",
                "amount", 5_000_000L
        ))));
        when(payFeignClient.queryUserOrderList(any())).thenReturn(Map.of(
                "data", Map.of("orderList", List.of())
        ));

        Map<String, Object> summary = controller.accountSummary().getData();
        List<Map<String, Object>> ledger = (List<Map<String, Object>>) summary.get("quotaLedger");

        assertEquals(1, ledger.size());
        assertEquals("MONTHLY_GRANT", ledger.get(0).get("type"));
        assertEquals(List.of(), summary.get("pendingGroupOrders"));
    }

    @Test
    void pricing_keepsMyTeamsSeparateFromTeamsForOtherUsers() {
        Map<String, Object> sku = new HashMap<>();
        sku.put("code", "QUOTA_LIGHT");
        sku.put("name", "轻享额度包");
        sku.put("price", 12);
        sku.put("baseQuota", 60);
        sku.put("groupGoodsId", "9890002");
        when(memberFeignClient.listSkus()).thenReturn(Result.success(List.of(sku)));

        Map<String, Object> ownTeam = Map.of("userId", "1001", "teamId", "team-own");
        Map<String, Object> otherTeam = Map.of("userId", "2002", "teamId", "team-other");
        Map<String, Object> market = new HashMap<>();
        market.put("activityId", 100201L);
        market.put("targetCount", 3);
        market.put("goods", Map.of("payPrice", 10.8, "deductionPrice", 1.2, "originalPrice", 12));
        market.put("teamList", List.of(ownTeam, otherTeam));
        when(groupFeignClient.queryGroupBuyMarketConfig(any())).thenReturn(Map.of(
                "code", "0000",
                "data", market
        ));

        Map<String, Object> data = controller.pricing().getData();
        Map<String, Object> groupBuy = (Map<String, Object>) data.get("groupBuy");

        assertEquals(List.of(ownTeam), groupBuy.get("myTeamList"));
        assertEquals(List.of(otherTeam), groupBuy.get("teamList"));
    }

    private Map<String, Object> payResponse(String status, String orderId) {
        Map<String, Object> order = new HashMap<>();
        order.put("orderId", orderId);
        order.put("status", status);
        order.put("marketType", 1);
        order.put("productName", "轻量额度包（60）");
        order.put("payAmount", 9.9);
        order.put("payTime", "2026-07-07T10:00:00");

        Map<String, Object> data = new HashMap<>();
        data.put("orderList", List.of(order));

        Map<String, Object> response = new HashMap<>();
        response.put("data", data);
        return response;
    }
}
