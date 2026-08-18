package com.aigroup.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaPublisherConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublisherConfiguration.class);

    static final String TOPIC_TEAM_SUCCESS = "group.team_success";
    static final String TOPIC_TEAM_REFUND = "group.team_refund";
    static final String TOPIC_MEMBER_BENEFIT = "member.benefit.completed";
    static final String TOPIC_USER_REGISTERED = "auth.user_registered";

    @Bean
    @ConditionalOnMissingBean
    ConfirmedKafkaPublisher confirmedKafkaPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${ai-group.kafka.ack-timeout-ms:5000}") long ackTimeoutMillis) {
        return new ConfirmedKafkaPublisher(kafkaTemplate, ackTimeoutMillis);
    }

    /**
     * Wins over Boot's recoverer-less {@code DefaultErrorHandler}. Original topics
     * retry 3 times with 1s backoff, then publish to {@code {topic}.DLT}.
     */
    @Bean
    @Primary
    DefaultErrorHandler kafkaDefaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    /**
     * DLT containers must not republish to {@code *.DLT.DLT}. Failures are logged
     * as {@code kafka.dlt.exhausted} and the record is considered recovered.
     */
    @Bean
    DefaultErrorHandler dltListenerErrorHandler() {
        return new DefaultErrorHandler((record, exception) ->
                log.error("kafka.dlt.exhausted topic={} partition={} offset={} key={}",
                        record.topic(), record.partition(), record.offset(), record.key(), exception),
                new FixedBackOff(0L, 0L));
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    ConcurrentKafkaListenerContainerFactory<String, String> dltKafkaListenerContainerFactory(
            ConsumerFactory consumerFactory,
            @Qualifier("dltListenerErrorHandler") DefaultErrorHandler dltListenerErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(dltListenerErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
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
