package com.aigroup.paymall.test.config;

import com.aigroup.paymall.config.FeignAuthConfig;
import org.junit.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PayConfigurationContractTest {

    @Test
    public void onlyDevDefaultsToTheLocalMemberServicePort() throws IOException {
        assertEquals("${MEMBER_SERVICE_URL:" + FeignAuthConfig.DEFAULT_MEMBER_SERVICE_URL + "}",
                property("application-dev.yml", "app.config.member-service.api-url"));
        assertEquals("${MEMBER_SERVICE_URL:}",
                property("application-prod.yml", "app.config.member-service.api-url"));
        assertEquals("http://127.0.0.1:18082", FeignAuthConfig.DEFAULT_MEMBER_SERVICE_URL);
    }

    @Test
    public void commonKafkaAndOutboxPublisherGatesRemainEnabled() throws IOException {
        assertEquals("all", property("application.yml", "spring.kafka.producer.acks"));
        assertEquals("manual", property("application.yml", "spring.kafka.listener.ack-mode"));
        assertEquals("${KAFKA_MEMBER_BENEFIT:member.benefit.completed}", property(
                "application.yml", "ai-group.kafka.topics.member-benefit"));
        assertEquals("com.zaxxer.hikari.HikariDataSource", property(
                "application-dev.yml", "spring.datasource.type"));
        assertEquals("Retail_HikariCP", property(
                "application-dev.yml", "spring.datasource.hikari.pool-name"));
        assertEquals("com.zaxxer.hikari.HikariDataSource", property(
                "application-prod.yml", "spring.datasource.type"));
        assertEquals("Retail_HikariCP", property(
                "application-prod.yml", "spring.datasource.hikari.pool-name"));
    }

    @Test
    public void outboxScanPrioritizesRevokeTombstonesBeforeGrants() throws IOException {
        ClassPathResource mapper = new ClassPathResource("mybatis/mapper/benefit_event_mapper.xml");
        String xml = new String(mapper.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertTrue(xml.contains(
                "order by case when event_type = 'GROUP_BUY_REVOKED' then 0 else 1 end, id asc"));
    }

    private Object property(String resourceName, String key) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                resourceName, new ClassPathResource(resourceName));
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
