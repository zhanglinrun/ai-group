package com.linrun.agent.test;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 配置文件加载回归。
 * 只验证 Spring Boot 默认配置装配结果，不拉起业务 Bean，避免噪音干扰根因判断。
 */
public class ConfigProfileLoadingTest {

    @Test
    public void shouldLoadMysqlDatasourcePropertiesFromDefaultConfigurationChain() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ConfigProbeApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--spring.profiles.active=dev")) {
            Assert.assertEquals("dev", context.getEnvironment().getProperty("spring.profiles.active"));
            Assert.assertEquals("com.mysql.cj.jdbc.Driver",
                    context.getEnvironment().getProperty("spring.datasource.mysql.driver-class-name"));
            Assert.assertNotNull(context.getEnvironment().getProperty("spring.datasource.mysql.url"));
            Assert.assertNotNull(context.getEnvironment().getProperty("spring.ai.openai.embedding.base-url"));
            Assert.assertEquals("text-embedding-v3",
                    context.getEnvironment().getProperty("spring.ai.openai.embedding.options.model"));
            Assert.assertEquals("true",
                    context.getEnvironment().getProperty("autobots.autoagent.skill.enabled"));
            Assert.assertEquals("runtime/skills",
                    context.getEnvironment().getProperty("autobots.autoagent.skill.directories[0]"));
            Assert.assertEquals("40",
                    context.getEnvironment().getProperty("autobots.autoagent.agent-loop.max-turns"));
            Assert.assertEquals("64",
                    context.getEnvironment().getProperty("autobots.autoagent.agent-loop.max-tool-calls"));
            Assert.assertEquals("3",
                    context.getEnvironment().getProperty("autobots.autoagent.agent-loop.max-completion-attempts"));
            Assert.assertEquals("1800",
                    context.getEnvironment().getProperty("autobots.autoagent.agent-loop.max-duration-seconds"));
            Assert.assertEquals("200000",
                    context.getEnvironment().getProperty("autobots.autoagent.agent-loop.max-total-tokens"));
            Assert.assertEquals("10000000",
                    context.getEnvironment().getProperty("autobots.autoagent.agent-loop.max-microcredits"));
        }
    }

    @TestConfiguration
    static class ConfigProbeApplication {
    }
}
