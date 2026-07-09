package com.aigroup.paymall.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dead-letter infrastructure for the s-pay-mall consumer queues (C5).
 * <p>
 * The three consumer queues ({@code s_pay_mall_queue_2_topic_team_success},
 * {@code ..._topic_team_refund}, {@code ..._order_pay_success}) declare
 * {@code x-dead-letter-exchange} / {@code x-dead-letter-routing-key} arguments
 * on their {@code @RabbitListener} bindings. After listener retry is exhausted
 * (see {@code spring.rabbitmq.listener.simple.retry}, requeue disabled), the
 * broker routes the poison message through the DLX declared here into the
 * matching DLQ for manual inspection/replay, instead of redelivering forever.
 * This is what makes the C3 "refund failure throws" change safe.
 * <p>
 * NOTE (local/rebuild): RabbitMQ rejects re-declaring an existing queue with
 * different arguments (PRECONDITION_FAILED). If the main queues already exist
 * from an older run WITHOUT the dead-letter arguments, delete them once
 * (management UI or {@code rabbitmqctl delete_queue}) and let the app
 * re-declare them on startup.
 */
@Configuration
public class RabbitMQDlqConfig {

    @Value("${spring.rabbitmq.config.consumer.dlx_exchange}")
    private String dlxExchange;

    @Value("${spring.rabbitmq.config.consumer.topic_team_success.dlq}")
    private String teamSuccessDlq;

    @Value("${spring.rabbitmq.config.consumer.topic_team_success.dlq_routing_key}")
    private String teamSuccessDlqRoutingKey;

    @Value("${spring.rabbitmq.config.consumer.topic_team_refund.dlq}")
    private String teamRefundDlq;

    @Value("${spring.rabbitmq.config.consumer.topic_team_refund.dlq_routing_key}")
    private String teamRefundDlqRoutingKey;

    @Value("${spring.rabbitmq.config.consumer.topic_order_pay_success.dlq}")
    private String orderPaySuccessDlq;

    @Value("${spring.rabbitmq.config.consumer.topic_order_pay_success.dlq_routing_key}")
    private String orderPaySuccessDlqRoutingKey;

    @Bean
    public TopicExchange sPayMallDlxExchange() {
        return new TopicExchange(dlxExchange, true, false);
    }

    @Bean
    public Queue teamSuccessDlqQueue() {
        return QueueBuilder.durable(teamSuccessDlq).build();
    }

    @Bean
    public Binding teamSuccessDlqBinding() {
        return BindingBuilder.bind(teamSuccessDlqQueue())
                .to(sPayMallDlxExchange())
                .with(teamSuccessDlqRoutingKey);
    }

    @Bean
    public Queue teamRefundDlqQueue() {
        return QueueBuilder.durable(teamRefundDlq).build();
    }

    @Bean
    public Binding teamRefundDlqBinding() {
        return BindingBuilder.bind(teamRefundDlqQueue())
                .to(sPayMallDlxExchange())
                .with(teamRefundDlqRoutingKey);
    }

    @Bean
    public Queue orderPaySuccessDlqQueue() {
        return QueueBuilder.durable(orderPaySuccessDlq).build();
    }

    @Bean
    public Binding orderPaySuccessDlqBinding() {
        return BindingBuilder.bind(orderPaySuccessDlqQueue())
                .to(sPayMallDlxExchange())
                .with(orderPaySuccessDlqRoutingKey);
    }

}
