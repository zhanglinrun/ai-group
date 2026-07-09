package com.aigroup.groupbuy.test;

import com.aigroup.groupbuy.infrastructure.event.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ApiTest {

    @Resource
    private EventPublisher publisher;

    @Value("${spring.rabbitmq.config.producer.topic_team_success.routing_key}")
    private String routingKey;

    @Resource
    private RedissonClient redissonClient;

    @Test
    public void test_lock_thread_1() throws InterruptedException {
        RLock lock = redissonClient.getLock("group_buy_market_notify_job_exec");
//        boolean b = lock.tryLock(3, 0, TimeUnit.SECONDS);
        boolean b = lock.tryLock(3, 3, TimeUnit.SECONDS);

        Thread.sleep(3000);

        log.info("娴嬭瘯缁撴灉:{}", b);
    }

    @Test
    public void test_lock_thread_2() throws InterruptedException {
        RLock lock = redissonClient.getLock("group_buy_market_notify_job_exec");
//        boolean b = lock.tryLock(3, 0, TimeUnit.SECONDS);
        boolean b = lock.tryLock(3, 3, TimeUnit.SECONDS);

        log.info("娴嬭瘯缁撴灉:{}", b);
    }

    @Test
    public void test_rabbitmq() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);

        publisher.publish(routingKey, "璁㈠崟缁撶畻锛歄RD-20231234");
        publisher.publish(routingKey, "璁㈠崟缁撶畻锛歄RD-20231235");
        publisher.publish(routingKey, "璁㈠崟缁撶畻锛歄RD-20231236");
        publisher.publish(routingKey, "璁㈠崟缁撶畻锛歄RD-20231237");
        publisher.publish(routingKey, "璁㈠崟缁撶畻锛歄RD-20231238");

        // 绛夊緟锛屾秷鎭秷璐广?傛祴璇曞悗锛屽彲涓诲姩鍏抽棴銆?
        countDownLatch.await();
    }

    @Test
    public void test_Supplier() {
        // 鍒涘缓涓?涓?Supplier 瀹炰緥锛岃繑鍥炰竴涓瓧绗︿覆
        Supplier<String> stringSupplier = () -> "Hello, XFG!";

        // 浣跨敤 get() 鏂规硶鑾峰彇 Supplier 鎻愪緵鐨勫??
        String result = stringSupplier.get();

        // 杈撳嚭缁撴灉
        System.out.println(result);

        // 鍙︿竴涓ず渚嬶紝浣跨敤 Supplier 鎻愪緵褰撳墠鏃堕棿
        Supplier<Long> currentTimeSupplier = System::currentTimeMillis;

        // 鑾峰彇褰撳墠鏃堕棿
        Long currentTime = currentTimeSupplier.get();

        // 杈撳嚭褰撳墠鏃堕棿
        System.out.println("Current time in milliseconds: " + currentTime);
    }

}
