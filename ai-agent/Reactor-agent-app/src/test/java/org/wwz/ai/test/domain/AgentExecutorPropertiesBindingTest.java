package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.types.agent.config.AgentExecutorProperties;

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
}
