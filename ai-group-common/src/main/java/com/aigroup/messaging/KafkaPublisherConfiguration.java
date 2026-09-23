package com.aigroup.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;

@Configuration
class KafkaPublisherConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublisherConfiguration.class);


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
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    /** DLT failures stop the container, leaving the record for operator intervention. */
    @Bean
    CommonContainerStoppingErrorHandler dltListenerErrorHandler() {
        return new CommonContainerStoppingErrorHandler() {
            @Override
            public void handleRemaining(Exception exception, List<ConsumerRecord<?, ?>> records,
                    Consumer<?, ?> consumer, MessageListenerContainer container) {
                if (!records.isEmpty()) {
                    ConsumerRecord<?, ?> record = records.get(0);
                    log.error("kafka.dlt.stopped topic={} partition={} offset={} key={}",
                            record.topic(), record.partition(), record.offset(), record.key(), exception);
                } else {
                    log.error("kafka.dlt.stopped container={}", container.getListenerId(), exception);
                }
                super.handleRemaining(exception, records, consumer, container);
            }
        };
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    ConcurrentKafkaListenerContainerFactory<String, String> dltKafkaListenerContainerFactory(
            ConsumerFactory consumerFactory,
            @Qualifier("dltListenerErrorHandler") CommonContainerStoppingErrorHandler dltListenerErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(dltListenerErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }

    @Value("${ai-group.kafka.topic-partitions:3}")
    private int topicPartitions;

    @Bean
    NewTopic topicTeamSuccess(@Value("${ai-group.kafka.topics.team-success:group.team_success}") String name) {
        return topic(name);
    }

    @Bean
    NewTopic topicTeamSuccessDlt(@Value("${ai-group.kafka.topics.team-success:group.team_success}") String name) {
        return topic(name + ".DLT");
    }

    @Bean
    NewTopic topicTeamRefund(@Value("${ai-group.kafka.topics.team-refund:group.team_refund}") String name) {
        return topic(name);
    }

    @Bean
    NewTopic topicTeamRefundDlt(@Value("${ai-group.kafka.topics.team-refund:group.team_refund}") String name) {
        return topic(name + ".DLT");
    }

    @Bean
    NewTopic topicMemberBenefit(@Value("${ai-group.kafka.topics.member-benefit:member.benefit.completed}") String name) {
        return topic(name);
    }

    @Bean
    NewTopic topicMemberBenefitDlt(@Value("${ai-group.kafka.topics.member-benefit:member.benefit.completed}") String name) {
        return topic(name + ".DLT");
    }

    @Bean
    NewTopic topicUserRegistered(@Value("${ai-group.kafka.topics.user-registered:auth.user_registered}") String name) {
        return topic(name);
    }

    @Bean
    NewTopic topicUserRegisteredDlt(@Value("${ai-group.kafka.topics.user-registered:auth.user_registered}") String name) {
        return topic(name + ".DLT");
    }

    private NewTopic topic(String name) {
        int partitions = Math.max(1, topicPartitions);
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}
