package com.aigroup.groupbuy.infrastructure.gateway;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 成团回调向 pay 发送内部 token，不依赖 Spring 容器与 MySQL。
 */
public class GroupBuyNotifyServiceHeaderTest {

    private MockWebServer server;
    private GroupBuyNotifyService notifyService;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("success"));
        server.start();

        notifyService = new GroupBuyNotifyService();
        ReflectionTestUtils.setField(notifyService, "okHttpClient", new OkHttpClient());
        ReflectionTestUtils.setField(notifyService, "internalToken", "secret-internal-token");
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void shouldAttachInternalTokenOnGroupBuyNotify() throws Exception {
        String response = notifyService.groupBuyNotify(
                server.url("/api/v1/alipay/group_buy_notify").toString(),
                "{\"orderId\":\"o-1\"}");

        assertEquals("success", response);
        RecordedRequest request = server.takeRequest();
        assertNotNull(request.getHeader("X-Internal-Token"));
        assertEquals("secret-internal-token", request.getHeader("X-Internal-Token"));
    }
}
