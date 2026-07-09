package org.wwz.ai.application.agent.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单用户并发对话限流。
 *
 * <p>全局 dispatch 线程池只能限制总并发，单个用户仍可刷满线程池影响他人。
 * 本限流器按 ownerId 维度限制在途对话数，超过上限即拒绝，保障多用户下的公平性并控制 LLM 调用成本。</p>
 */
@Component
public class PerUserConcurrencyLimiter {

    private final int maxPerUser;
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public PerUserConcurrencyLimiter(
            @Value("${autobots.autoagent.max-concurrent-dialogues-per-user:3}") int maxPerUser) {
        this.maxPerUser = maxPerUser;
    }

    /**
     * 尝试为用户占用一个在途名额。
     *
     * @return true 表示占用成功（调用方必须在结束时 release）；false 表示已达上限被拒
     */
    public boolean tryAcquire(String ownerId) {
        if (ownerId == null || maxPerUser <= 0) {
            return true;
        }
        AtomicInteger counter = inFlight.computeIfAbsent(ownerId, k -> new AtomicInteger());
        if (counter.incrementAndGet() > maxPerUser) {
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * 释放一个在途名额。与成功的 tryAcquire 成对调用。
     */
    public void release(String ownerId) {
        if (ownerId == null || maxPerUser <= 0) {
            return;
        }
        AtomicInteger counter = inFlight.get(ownerId);
        if (counter != null) {
            int current = counter.decrementAndGet();
            if (current < 0) {
                // 防御：不应发生，纠正为 0，避免负计数长期占用名额
                counter.incrementAndGet();
            }
        }
    }

    /**
     * 当前用户在途对话数（用于测试/观测）。
     */
    public int currentInFlight(String ownerId) {
        AtomicInteger counter = ownerId == null ? null : inFlight.get(ownerId);
        return counter == null ? 0 : Math.max(0, counter.get());
    }
}
