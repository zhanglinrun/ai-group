package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.types.agent.config.AgentExecutorProperties;

/**
 * executor / CORS 属性对象默认值测试。
 */
public class AgentExecutorPropertiesBindingTest {

    @Test
    public void shouldExposeCorsDefaults() {
        AgentExecutorProperties properties = new AgentExecutorProperties();

        Assert.assertNotNull(properties.getCors());
        Assert.assertNotNull(properties.getCors().getAllowedOrigins());
        Assert.assertTrue(properties.getCors().getAllowedOrigins().isEmpty());
    }

    @Test
    public void shouldExposeSafeRunRecoveryDefaults() {
        AgentExecutorProperties.RunRecovery recovery = new AgentExecutorProperties().getRunRecovery();

        Assert.assertNotNull(recovery);
        Assert.assertTrue(recovery.getEnabled());
        Assert.assertEquals(Long.valueOf(60_000L), recovery.getScanIntervalMillis());
        Assert.assertEquals(Long.valueOf(300_000L), recovery.getDeadlineGraceMillis());
        Assert.assertEquals(Long.valueOf(60_000L), recovery.getHeartbeatTimeoutMillis());
        Assert.assertEquals(Integer.valueOf(200), recovery.getBatchLimit());
    }
}
