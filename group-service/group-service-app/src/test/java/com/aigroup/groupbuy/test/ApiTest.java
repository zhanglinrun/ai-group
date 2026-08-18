package com.aigroup.groupbuy.test;

import com.aigroup.groupbuy.infrastructure.event.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.concurrent.CountDownLatch;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ApiTest {

    @Resource
    private EventPublisher publisher;

    @Value("${ai-group.kafka.topics.team-success:group.team_success}")
    private String topic;

    @Ignore("Requires a running Kafka broker and is not part of the unit suite")
    @Test
    public void test_kafka_publish() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);

        publisher.publish(topic, "订单结算：ORD-20231234");
        publisher.publish(topic, "订单结算：ORD-20231235");
        publisher.publish(topic, "订单结算：ORD-20231236");
        publisher.publish(topic, "订单结算：ORD-20231237");
        publisher.publish(topic, "订单结算：ORD-20231238");

        // 等待消息消费。测试后，可主动关闭。
        countDownLatch.await();
    }

}
