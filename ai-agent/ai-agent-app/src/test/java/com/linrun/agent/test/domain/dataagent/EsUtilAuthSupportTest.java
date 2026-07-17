package com.linrun.agent.test.domain.dataagent;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.reactor.util.ESUtil;

/**
 * ES 认证头生成测试。
 */
public class EsUtilAuthSupportTest {

    @Test
    public void shouldPreferApiKeyAuthorizationHeader() {
        String headerValue = ESUtil.resolveAuthorizationHeaderValue("elastic", "pwd", "encoded-api-key");

        Assert.assertEquals("ApiKey encoded-api-key", headerValue);
    }

    @Test
    public void shouldFallbackToBasicAuthorizationHeader() {
        String headerValue = ESUtil.resolveAuthorizationHeaderValue("elastic", "pwd", null);

        Assert.assertNotNull(headerValue);
        Assert.assertTrue(headerValue.startsWith("Basic "));
    }
}
