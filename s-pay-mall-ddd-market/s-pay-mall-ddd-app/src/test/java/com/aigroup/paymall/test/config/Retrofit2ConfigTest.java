package com.aigroup.paymall.test.config;

import com.aigroup.paymall.config.Retrofit2Config;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.dto.LockMarketPayOrderRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.RefundMarketPayOrderRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.SettlementMarketPayOrderRequestDTO;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * pay → group Retrofit 出站必须附带内部 token。
 */
public class Retrofit2ConfigTest {

    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void shouldAttachSingleInternalTokenOnLockSettlementAndRefund() throws Exception {
        enqueueOk();
        enqueueOk();
        enqueueOk();

        IGroupBuyMarketService service = buildService("secret-internal-token");

        LockMarketPayOrderRequestDTO lock = new LockMarketPayOrderRequestDTO();
        lock.setUserId("u1");
        service.lockMarketPayOrder(lock).execute();

        SettlementMarketPayOrderRequestDTO settlement = new SettlementMarketPayOrderRequestDTO();
        settlement.setUserId("u1");
        settlement.setOutTradeNo("o1");
        settlement.setOutTradeTime(new Date());
        service.settlementMarketPayOrder(settlement).execute();

        service.refundMarketPayOrder(RefundMarketPayOrderRequestDTO.builder()
                .userId("u1")
                .outTradeNo("o1")
                .build()).execute();

        assertTokenOn(server.takeRequest(), "/api/v1/gbm/trade/lock_market_pay_order");
        assertTokenOn(server.takeRequest(), "/api/v1/gbm/trade/settlement_market_pay_order");
        assertTokenOn(server.takeRequest(), "/api/v1/gbm/trade/refund_market_pay_order");
    }

    @Test
    public void shouldOmitTokenWhenBlank() throws Exception {
        enqueueOk();
        IGroupBuyMarketService service = buildService("");
        LockMarketPayOrderRequestDTO lock = new LockMarketPayOrderRequestDTO();
        lock.setUserId("u1");
        service.lockMarketPayOrder(lock).execute();
        RecordedRequest request = server.takeRequest();
        assertNull(request.getHeader(Retrofit2Config.HEADER_INTERNAL_TOKEN));
    }

    private IGroupBuyMarketService buildService(String token) {
        Retrofit2Config config = new Retrofit2Config();
        ReflectionTestUtils.setField(config, "groupBuyMarketApiUrl", server.url("/").toString());
        ReflectionTestUtils.setField(config, "internalToken", token);
        return config.groupBuyMarketService();
    }

    private void enqueueOk() {
        server.enqueue(new MockResponse()
                .setBody("{\"code\":\"0000\",\"info\":\"success\",\"data\":{}}")
                .addHeader("Content-Type", "application/json"));
    }

    private void assertTokenOn(RecordedRequest request, String expectedPath) {
        assertNotNull(request);
        assertEquals(expectedPath, request.getPath());
        assertEquals("secret-internal-token", request.getHeader(Retrofit2Config.HEADER_INTERNAL_TOKEN));
        assertEquals(1, request.getHeaders().values(Retrofit2Config.HEADER_INTERNAL_TOKEN).size());
    }
}
