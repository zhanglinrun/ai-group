package com.aigroup.paymall.test.config;

import com.aigroup.paymall.config.Retrofit2Config;
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
    public void devAndProdDefaultToTheActualMemberServicePort() throws IOException {
        String expected = "${MEMBER_SERVICE_URL:" + Retrofit2Config.DEFAULT_MEMBER_SERVICE_URL + "}";

        assertEquals(expected, property("application-dev.yml", "app.config.member-service.api-url"));
        assertEquals(expected, property("application-prod.yml", "app.config.member-service.api-url"));
        assertEquals("http://127.0.0.1:18082", Retrofit2Config.DEFAULT_MEMBER_SERVICE_URL);
    }

    @Test
    public void commonRabbitAndOutboxPublisherGatesRemainEnabled() throws IOException {
        assertEquals("correlated", property("application.yml", "spring.rabbitmq.publisher-confirm-type"));
        assertEquals(Boolean.TRUE, property("application.yml", "spring.rabbitmq.publisher-returns"));
        assertEquals(Boolean.TRUE, property("application.yml", "spring.rabbitmq.template.mandatory"));
        assertEquals("member.benefit.exchange", property(
                "application.yml", "spring.rabbitmq.config.producer.member_benefit.exchange"));
        assertEquals("s_pay_mall_queue_2_order_pay_success", property(
                "application.yml", "spring.rabbitmq.config.consumer.topic_order_pay_success.queue"));
        assertEquals("${PAY_OUTBOX_PUBLISH_DELAY_MS:1000}",
                property("application.yml", "ai-group.pay.outbox.publish-delay-ms"));
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
