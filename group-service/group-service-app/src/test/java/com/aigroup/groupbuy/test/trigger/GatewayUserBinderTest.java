package com.aigroup.groupbuy.test.trigger;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.groupbuy.api.dto.GoodsMarketRequestDTO;
import com.aigroup.groupbuy.api.dto.GoodsMarketResponseDTO;
import com.aigroup.groupbuy.api.dto.LockMarketPayOrderRequestDTO;
import com.aigroup.groupbuy.api.dto.LockMarketPayOrderResponseDTO;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.trigger.http.MarketIndexController;
import com.aigroup.groupbuy.trigger.http.MarketTradeController;
import com.aigroup.groupbuy.trigger.http.support.GatewayUserBinder;
import org.junit.After;
import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class GatewayUserBinderTest {

    @After
    public void clearUser() {
        RequestUserContext.clear();
    }

    @Test
    public void requireUserIdUsesBoundJwtNotBody() {
        RequestUserContext.bind(42L, "alice", "USER");
        assertEquals("42", GatewayUserBinder.requireUserId("42"));
        assertEquals("42", GatewayUserBinder.requireUserId(null));
    }

    @Test
    public void requireUserIdRejectsBodyMismatch() {
        RequestUserContext.bind(42L, "alice", "USER");
        try {
            GatewayUserBinder.requireUserId("999");
            fail("body userId must not override JWT");
        } catch (IllegalArgumentException expected) {
            assertEquals("user identity mismatch", expected.getMessage());
        }
    }

    @Test
    public void requireUserIdRejectsMissingContext() {
        try {
            GatewayUserBinder.requireUserId("42");
            fail("unbound request must fail");
        } catch (IllegalStateException expected) {
            assertEquals("missing authenticated user", expected.getMessage());
        }
    }

    @Test
    public void indexRejectsForgedBodyUserId() {
        RequestUserContext.bind(42L, "alice", "USER");
        MarketIndexController controller = new MarketIndexController();
        GoodsMarketRequestDTO request = new GoodsMarketRequestDTO();
        request.setUserId("999");
        request.setSource("s01");
        request.setChannel("c01");
        request.setGoodsId("9890002");

        Response<GoodsMarketResponseDTO> response = controller.queryGroupBuyMarketConfig(request);

        assertEquals("0002", response.getCode());
        assertEquals("user identity mismatch", response.getInfo());
    }

    @Test
    public void lockRejectsMissingJwt() {
        MarketTradeController controller = new MarketTradeController();
        LockMarketPayOrderRequestDTO request = new LockMarketPayOrderRequestDTO();
        request.setUserId("42");
        request.setActivityId(100201L);
        request.setGoodsId("9890002");
        request.setOrderPrice(new BigDecimal("12.00"));
        request.setSource("s01");
        request.setChannel("c01");
        request.setOutTradeNo("pay-1");
        request.setNotifyMQ();

        Response<LockMarketPayOrderResponseDTO> response = controller.lockMarketPayOrder(request);

        assertEquals("0002", response.getCode());
        assertEquals("missing authenticated user", response.getInfo());
    }
}
