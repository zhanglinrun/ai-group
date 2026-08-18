package com.aigroup.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnBean(KafkaTemplate.class)
class KafkaPublisherConfiguration {

    static final String TOPIC_TEAM_SUCCESS = "group.team_success";
    static final String TOPIC_TEAM_REFUND = "group.team_refund";
    static final String TOPIC_ORDER_PAY_SUCCESS = "pay.order_pay_success";
    static final String TOPIC_MEMBER_BENEFIT = "member.benefit.completed";
    static final String TOPIC_USER_REGISTERED = "auth.user_registered";

    @Bean
    @ConditionalOnMissingBean
    ConfirmedKafkaPublisher confirmedKafkaPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${ai-group.kafka.ack-timeout-ms:5000}") long ackTimeoutMillis) {
        return new ConfirmedKafkaPublisher(kafkaTemplate, ackTimeoutMillis);
    }

    @Bean
    @ConditionalOnMissingBean
    DefaultErrorHandler kafkaDefaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    @Bean
    NewTopic topicTeamSuccess() {
        return topic(TOPIC_TEAM_SUCCESS);
    }

    @Bean
    NewTopic topicTeamSuccessDlt() {
        return topic(TOPIC_TEAM_SUCCESS + ".DLT");
    }

    @Bean
    NewTopic topicTeamRefund() {
        return topic(TOPIC_TEAM_REFUND);
    }

    @Bean
    NewTopic topicTeamRefundDlt() {
        return topic(TOPIC_TEAM_REFUND + ".DLT");
    }

    @Bean
    NewTopic topicOrderPaySuccess() {
        return topic(TOPIC_ORDER_PAY_SUCCESS);
    }

    @Bean
    NewTopic topicOrderPaySuccessDlt() {
        return topic(TOPIC_ORDER_PAY_SUCCESS + ".DLT");
    }

    @Bean
    NewTopic topicMemberBenefit() {
        return topic(TOPIC_MEMBER_BENEFIT);
    }

    @Bean
    NewTopic topicMemberBenefitDlt() {
        return topic(TOPIC_MEMBER_BENEFIT + ".DLT");
    }

    @Bean
    NewTopic topicUserRegistered() {
        return topic(TOPIC_USER_REGISTERED);
    }

    @Bean
    NewTopic topicUserRegisteredDlt() {
        return topic(TOPIC_USER_REGISTERED + ".DLT");
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
