package com.aigroup.groupbuy.infrastructure.event;

import com.aigroup.messaging.ConfirmedKafkaPublisher;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class EventPublisherTest {

    @Test
    public void publishDelegatesTopicKeyAndPayload() {
        ConfirmedKafkaPublisher kafkaPublisher = mock(ConfirmedKafkaPublisher.class);
        EventPublisher publisher = new EventPublisher(kafkaPublisher);

        publisher.publish("group.team_success", "team-1", "payload");

        verify(kafkaPublisher).publish("group.team_success", "team-1", "payload");
    }

    @Test
    public void twoArgPublishUsesTopicAsKey() {
        ConfirmedKafkaPublisher kafkaPublisher = mock(ConfirmedKafkaPublisher.class);
        EventPublisher publisher = new EventPublisher(kafkaPublisher);

        publisher.publish("group.team_success", "payload");

        verify(kafkaPublisher).publish("group.team_success", "group.team_success", "payload");
    }
}
