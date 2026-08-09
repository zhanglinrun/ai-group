package com.aigroup.groupbuy.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the group service's durable RabbitMQ topic bindings. */
@Configuration
public class RabbitEventConfiguration {

    @Bean
    TopicExchange xiongdoctorEventExchange(
            @Value("${spring.rabbitmq.event-exchange:xiongdoctor.events}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Queue groupTeamRefundQueue() {
        return QueueBuilder.durable("group-service.team-refund").build();
    }

    @Bean
    Binding groupTeamRefundBinding(
            Queue groupTeamRefundQueue,
            TopicExchange xiongdoctorEventExchange,
            @Value("${spring.rabbitmq.routing.group-team-refund:group.team_refund}") String routingKey) {
        return BindingBuilder.bind(groupTeamRefundQueue).to(xiongdoctorEventExchange).with(routingKey);
    }

    @Bean
    Queue groupTeamSuccessQueue() {
        return QueueBuilder.durable("group-service.team-success").build();
    }

    @Bean
    Binding groupTeamSuccessBinding(
            Queue groupTeamSuccessQueue,
            TopicExchange xiongdoctorEventExchange,
            @Value("${spring.rabbitmq.routing.group-team-success:group.team_success}") String routingKey) {
        return BindingBuilder.bind(groupTeamSuccessQueue).to(xiongdoctorEventExchange).with(routingKey);
    }
}
