package com.linrun.agent.test.domain.dataagent;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.reactor.config.data.DataAgentConfig;
import com.linrun.agent.domain.agent.reactor.config.data.EsConfig;

/**
 * 共享云端向量配置绑定测试。
 */
public class CloudVectorConfigBindingTest {

    @Test
    public void shouldKeepSharedCloudFields() {
        DataAgentConfig dataAgentConfig = new DataAgentConfig();
        EsConfig esConfig = new EsConfig();

        dataAgentConfig.setAgentUrl("http://127.0.0.1:1601");
        dataAgentConfig.setForceRefresh(true);
        esConfig.setEnable(true);
        esConfig.setScheme("https");
        esConfig.setHost("es.example.com:9200");
        esConfig.setApiKey("es-api-key");
        dataAgentConfig.setEsConfig(esConfig);

        Assert.assertEquals("http://127.0.0.1:1601", dataAgentConfig.getAgentUrl());
        Assert.assertTrue(dataAgentConfig.getForceRefresh());
        Assert.assertEquals("https", dataAgentConfig.getEsConfig().getScheme());
        Assert.assertEquals("es-api-key", dataAgentConfig.getEsConfig().getApiKey());
    }

    @Test
    public void shouldKeepExplicitConfigOnly() {
        EsConfig esConfig = new EsConfig();
        DataAgentConfig dataAgentConfig = new DataAgentConfig();

        Assert.assertNull(esConfig.getEnable());
        Assert.assertNull(esConfig.getScheme());
        Assert.assertFalse(dataAgentConfig.getForceRefresh());
        Assert.assertNotNull(dataAgentConfig.getEsConfig());
        Assert.assertNotNull(dataAgentConfig.getDbConfig());
        Assert.assertNull(dataAgentConfig.getEsConfig().getEnable());
    }
}
