package com.aigroup.member.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable member benefit event binding on the shared RabbitMQ topic exchange. */
@Configuration
public class RabbitEventConfiguration {

    @Bean
    TopicExchange xiongdoctorEventExchange(
            @Value("${spring.rabbitmq.event-exchange:xiongdoctor.events}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Queue memberBenefitQueue() {
        return QueueBuilder.durable("member-service.benefit").build();
    }

    @Bean
    Binding memberBenefitBinding(
            Queue memberBenefitQueue,
            TopicExchange xiongdoctorEventExchange,
            @Value("${spring.rabbitmq.routing.member-benefit:member.benefit.completed}") String routingKey) {
        return BindingBuilder.bind(memberBenefitQueue).to(xiongdoctorEventExchange).with(routingKey);
    }

    @Bean
    Queue memberUserRegisteredQueue() {
        return QueueBuilder.durable("member-service.user-registered").build();
    }

    @Bean
    Binding memberUserRegisteredBinding(
            Queue memberUserRegisteredQueue,
            TopicExchange xiongdoctorEventExchange,
            @Value("${spring.rabbitmq.routing.member-user-registered:auth.user_registered}") String routingKey) {
        return BindingBuilder.bind(memberUserRegisteredQueue).to(xiongdoctorEventExchange).with(routingKey);
    }
}
