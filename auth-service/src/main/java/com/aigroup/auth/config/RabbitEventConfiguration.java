package com.aigroup.auth.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Auth only publishes to the shared durable event exchange. */
@Configuration
public class RabbitEventConfiguration {

    @Bean
    TopicExchange xiongdoctorEventExchange(
            @Value("${spring.rabbitmq.event-exchange:xiongdoctor.events}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }
}
