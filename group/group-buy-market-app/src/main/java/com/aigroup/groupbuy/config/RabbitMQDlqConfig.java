package com.aigroup.groupbuy.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dead-letter infrastructure for the group-buy-market consumer queues.
 * <p>
 * Consumer queues ({@code group_buy_market_queue_2_topic_team_success} /
 * {@code ..._team_refund}) declare {@code x-dead-letter-exchange} /
 * {@code x-dead-letter-routing-key} arguments on their {@code @RabbitListener}
 * bindings. After listener retry is exhausted (see
 * {@code spring.rabbitmq.listener.simple.retry}, requeue disabled), the broker
 * routes the poison message through the DLX declared here into the matching DLQ
 * for manual inspection/replay, instead of redelivering it forever.
 * <p>
 * NOTE (local/rebuild): RabbitMQ rejects re-declaring an existing queue with
 * different arguments (PRECONDITION_FAILED). If the main queues already exist
 * from an older run WITHOUT the dead-letter arguments, delete them once
 * (management UI or {@code rabbitmqctl delete_queue}) and let the app re-declare
 * them on startup.
 */
@Configuration
public class RabbitMQDlqConfig {

    @Value("${spring.rabbitmq.config.producer.dlx_exchange}")
    private String dlxExchange;

    @Value("${spring.rabbitmq.config.producer.topic_team_success.dlq}")
    private String teamSuccessDlq;

    @Value("${spring.rabbitmq.config.producer.topic_team_success.dlq_routing_key}")
    private String teamSuccessDlqRoutingKey;

    @Value("${spring.rabbitmq.config.producer.topic_team_refund.dlq}")
    private String teamRefundDlq;

    @Value("${spring.rabbitmq.config.producer.topic_team_refund.dlq_routing_key}")
    private String teamRefundDlqRoutingKey;

    @Bean
    public TopicExchange groupBuyMarketDlxExchange() {
        return new TopicExchange(dlxExchange, true, false);
    }

    @Bean
    public Queue teamSuccessDlqQueue() {
        return QueueBuilder.durable(teamSuccessDlq).build();
    }

    @Bean
    public Binding teamSuccessDlqBinding() {
        return BindingBuilder.bind(teamSuccessDlqQueue())
                .to(groupBuyMarketDlxExchange())
                .with(teamSuccessDlqRoutingKey);
    }

    @Bean
    public Queue teamRefundDlqQueue() {
        return QueueBuilder.durable(teamRefundDlq).build();
    }

    @Bean
    public Binding teamRefundDlqBinding() {
        return BindingBuilder.bind(teamRefundDlqQueue())
                .to(groupBuyMarketDlxExchange())
                .with(teamRefundDlqRoutingKey);
    }

}
