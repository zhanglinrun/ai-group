package com.aigroup.paymall.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BenefitRabbitMQConfig {

    @Bean
    public TopicExchange memberBenefitExchange(
            @Value("${spring.rabbitmq.config.producer.member_benefit.exchange}") String exchangeName) {
        return new TopicExchange(exchangeName, true, false);
    }

}
