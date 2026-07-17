package com.linrun.agent.test.domain;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.adapter.port.PlatformContextPort;
import com.linrun.agent.infrastructure.gateway.platform.PlatformBffClient;
import com.linrun.agent.infrastructure.gateway.platform.PlatformContextAdapter;
import com.linrun.agent.infrastructure.gateway.platform.dto.PlatformBffDtos;
import com.linrun.agent.infrastructure.gateway.platform.dto.PlatformBffResult;

import java.math.BigDecimal;
import java.util.List;

public class PlatformContextAdapterTest {

    @Test
    public void shouldPropagateTrustedOwnerAndDegradationWhileDroppingPaymentForm() {
        PlatformBffClient client = Mockito.mock(PlatformBffClient.class);
        PlatformBffDtos.OrderItemDto item = new PlatformBffDtos.OrderItemDto();
        item.setOrderId("order-1");
        item.setDisplayStatus("PAY_WAIT");
        item.setAmount(new BigDecimal("9.90"));
        item.setPayUrl("<form action='https://payment.example'>secret form</form>");
        PlatformBffDtos.OrdersDto data = new PlatformBffDtos.OrdersDto();
        data.setItems(List.of(item));
        data.setMeta(meta(true, "pay", "ORDER_LIST_PARTIAL"));
        Mockito.when(client.orders(77L)).thenReturn(success(data));
        PlatformContextAdapter adapter = new PlatformContextAdapter(client);

        PlatformContextPort.ContextResult<PlatformContextPort.Orders> result = adapter.orders(77L);

        Assert.assertTrue(result.meta().degraded());
        Assert.assertEquals("ORDER_LIST_PARTIAL", result.meta().errors().get(0).code());
        Assert.assertEquals("order-1", result.data().items().get(0).orderId());
        Assert.assertTrue(result.data().items().get(0).paymentActionAvailable());
        String domainJson = JSON.toJSONString(result);
        Assert.assertFalse(domainJson.contains("payUrl"));
        Assert.assertFalse(domainJson.contains("secret form"));
        Mockito.verify(client).orders(77L);
    }

    @Test
    public void shouldDeserializeAndMapRealBffFieldNamesForAllReadOperations() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PlatformBffClient client = Mockito.mock(PlatformBffClient.class);

        PlatformBffResult<PlatformBffDtos.AccountSummaryDto> account = mapper.readValue("""
                {"code":200,"message":"success","timestamp":1,"data":{
                  "userId":88,"freeQuotaBalance":100,"paidQuotaBalance":200,"frozenBalance":30,
                  "availableQuota":270,"quotaLedger":[],"pendingGroupOrders":[],
                  "meta":{"degraded":false,"errors":[]}}}
                """, new TypeReference<>() { });
        PlatformBffResult<PlatformBffDtos.PricingDto> pricing = mapper.readValue("""
                {"code":200,"message":"success","timestamp":2,"data":{
                  "skus":[{"code":"SKU-1","name":"100额度套餐","price":9.90,"baseQuota":100,
                    "groupGoodsId":"goods-1","groupActivityId":101,"groupPayPrice":7.90,
                    "groupDeductionPrice":2.00,"groupOriginalPrice":9.90,"groupActivityType":1,
                    "groupTiers":[{"tierNo":1,"tierName":"2人档","targetCount":2,"bonusQuota":10}]}],
                  "groupBuy":{"activityId":101,"activityType":1,"goods":{"goodsId":"goods-1",
                    "originalPrice":9.90,"deductionPrice":2.00,"payPrice":7.90},"tiers":[],"teamList":[],
                    "teamStatistic":{"allTeamCount":1,"allTeamCompleteCount":0,"allTeamUserCount":1}},
                  "meta":{"degraded":false,"errors":[]}}}
                """, new TypeReference<>() { });
        PlatformBffResult<PlatformBffDtos.GroupBuyDto> group = mapper.readValue("""
                {"code":200,"message":"success","timestamp":3,"data":{
                  "activityId":101,"groupBuy":{"activityId":101,"activityType":1,"tiers":[],
                    "teamList":[{"userId":"other-user-internal-id","teamId":"team-1","activityId":101,
                      "targetCount":3,"completeCount":1,"lockCount":1,"validEndTime":"2026-07-18T10:00:00"}]},
                  "skus":[],"meta":{"degraded":true,"errors":[{"service":"group",
                    "code":"GROUP_MARKET_UNAVAILABLE","message":"timeout"}]}}}
                """, new TypeReference<>() { });
        PlatformBffResult<PlatformBffDtos.OrdersDto> orders = mapper.readValue("""
                {"code":200,"message":"success","timestamp":4,"data":{
                  "items":[{"orderId":"order-9","status":"PAY_WAIT","displayStatus":"PAY_WAIT",
                    "productName":"100额度套餐","amount":9.90,"marketType":1,
                    "payUrl":"<form>private-payment-form</form>"}],
                  "meta":{"degraded":false,"errors":[]}}}
                """, new TypeReference<>() { });

        Mockito.when(client.accountSummary(88L)).thenReturn(account);
        Mockito.when(client.pricing(88L)).thenReturn(pricing);
        Mockito.when(client.groupBuy(88L, 101L)).thenReturn(group);
        Mockito.when(client.orders(88L)).thenReturn(orders);
        PlatformContextAdapter adapter = new PlatformContextAdapter(client);

        PlatformContextPort.ContextResult<PlatformContextPort.AccountSummary> accountResult =
                adapter.accountSummary(88L);
        PlatformContextPort.ContextResult<PlatformContextPort.Pricing> pricingResult = adapter.pricing(88L);
        PlatformContextPort.ContextResult<PlatformContextPort.GroupBuy> groupResult = adapter.groupBuy(88L, 101L);
        PlatformContextPort.ContextResult<PlatformContextPort.Orders> ordersResult = adapter.orders(88L);

        Assert.assertEquals(Long.valueOf(270L), accountResult.data().availableQuota());
        Assert.assertEquals("SKU-1", pricingResult.data().skus().get(0).code());
        Assert.assertEquals(new BigDecimal("7.90"), pricingResult.data().skus().get(0).groupPayPrice());
        Assert.assertEquals("team-1", groupResult.data().groupBuy().teamList().get(0).teamId());
        Assert.assertTrue(groupResult.meta().degraded());
        Assert.assertEquals("GROUP_MARKET_UNAVAILABLE", groupResult.meta().errors().get(0).code());
        Assert.assertTrue(ordersResult.data().items().get(0).paymentActionAvailable());

        String mappedJson = JSON.toJSONString(List.of(
                accountResult.data(), groupResult.data(), ordersResult.data()));
        Assert.assertFalse(mappedJson.contains("userId"));
        Assert.assertFalse(mappedJson.contains("ownerId"));
        Assert.assertFalse(mappedJson.contains("other-user-internal-id"));
        Assert.assertFalse(mappedJson.contains("payUrl"));
        Assert.assertFalse(mappedJson.contains("private-payment-form"));
    }

    @Test
    public void missingOrdersListMustFailInsteadOfMasqueradingAsEmptySuccess() {
        PlatformBffClient client = Mockito.mock(PlatformBffClient.class);
        PlatformBffDtos.OrdersDto data = new PlatformBffDtos.OrdersDto();
        data.setItems(null);
        data.setMeta(meta(false, null, null));
        Mockito.when(client.orders(8L)).thenReturn(success(data));

        try {
            new PlatformContextAdapter(client).orders(8L);
            Assert.fail("missing items must fail closed");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("orders.items"));
        }
    }

    @Test
    public void groupBuyWithoutActivityMustReuseReadOnlyPricingEndpoint() {
        PlatformBffClient client = Mockito.mock(PlatformBffClient.class);
        PlatformBffDtos.GroupBuyInfoDto groupBuy = new PlatformBffDtos.GroupBuyInfoDto();
        groupBuy.setActivityId(123L);
        groupBuy.setTeamList(List.of());
        PlatformBffDtos.PricingDto data = new PlatformBffDtos.PricingDto();
        data.setSkus(List.of());
        data.setGroupBuy(groupBuy);
        data.setMeta(meta(false, null, null));
        Mockito.when(client.pricing(5L)).thenReturn(success(data));

        PlatformContextPort.ContextResult<PlatformContextPort.GroupBuy> result =
                new PlatformContextAdapter(client).groupBuy(5L, null);

        Assert.assertEquals(Long.valueOf(123L), result.data().activityId());
        Mockito.verify(client).pricing(5L);
        Mockito.verify(client, Mockito.never()).groupBuy(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    public void missingBffMetaMustFailClosed() {
        PlatformBffClient client = Mockito.mock(PlatformBffClient.class);
        PlatformBffDtos.PricingDto data = new PlatformBffDtos.PricingDto();
        data.setSkus(List.of());
        data.setMeta(null);
        Mockito.when(client.pricing(3L)).thenReturn(success(data));

        try {
            new PlatformContextAdapter(client).pricing(3L);
            Assert.fail("missing degradation metadata must fail closed");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("degradation metadata"));
        }
    }

    private PlatformBffDtos.MetaDto meta(boolean degraded, String service, String code) {
        PlatformBffDtos.MetaDto meta = new PlatformBffDtos.MetaDto();
        meta.setDegraded(degraded);
        if (service == null) {
            meta.setErrors(List.of());
        } else {
            PlatformBffDtos.DegradationDto error = new PlatformBffDtos.DegradationDto();
            error.setService(service);
            error.setCode(code);
            error.setMessage("downstream unavailable");
            meta.setErrors(List.of(error));
        }
        return meta;
    }

    private <T> PlatformBffResult<T> success(T data) {
        return new PlatformBffResult<>(200, "success", data);
    }
}
