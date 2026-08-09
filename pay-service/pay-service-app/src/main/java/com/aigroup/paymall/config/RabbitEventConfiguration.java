package com.aigroup.paymall.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable topic bindings for payment-side settlement and refund consumers. */
@Configuration
public class RabbitEventConfiguration {

    @Bean
    TopicExchange xiongdoctorEventExchange(
            @Value("${spring.rabbitmq.event-exchange:xiongdoctor.events}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Queue payOrderSuccessQueue() {
        return QueueBuilder.durable("pay-service.order-pay-success").build();
    }

    @Bean
    Binding payOrderSuccessBinding(
            Queue payOrderSuccessQueue,
            TopicExchange xiongdoctorEventExchange,
            @Value("${spring.rabbitmq.routing.order-pay-success:pay.order_pay_success}") String routingKey) {
        return BindingBuilder.bind(payOrderSuccessQueue).to(xiongdoctorEventExchange).with(routingKey);
    }

    @Bean
    Queue payTeamSuccessQueue() {
        return QueueBuilder.durable("pay-service.team-success").build();
    }

    @Bean
    Binding payTeamSuccessBinding(
            Queue payTeamSuccessQueue,
            TopicExchange xiongdoctorEventExchange,
            @Value("${spring.rabbitmq.routing.group-team-success:group.team_success}") String routingKey) {
        return BindingBuilder.bind(payTeamSuccessQueue).to(xiongdoctorEventExchange).with(routingKey);
    }

    @Bean
    Queue payTeamRefundQueue() {
        return QueueBuilder.durable("pay-service.team-refund").build();
    }

    @Bean
    Binding payTeamRefundBinding(
            Queue payTeamRefundQueue,
            TopicExchange xiongdoctorEventExchange,
            @Value("${spring.rabbitmq.routing.group-team-refund:group.team_refund}") String routingKey) {
        return BindingBuilder.bind(payTeamRefundQueue).to(xiongdoctorEventExchange).with(routingKey);
    }
}
