package com.linrun.agent.domain.agent.reactor.model.multi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal ordering state shared by realtime and history projection. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventResult {

    @Builder.Default
    private Map<String, Integer> orderMapping = new HashMap<>();

    private String taskId;

    @Builder.Default
    private AtomicInteger taskOrder = new AtomicInteger(1);

    @Builder.Default
    private Map<String, Object> resultMap = new HashMap<>();

    public Integer getAndIncrOrder(String key) {
        int next = orderMapping.getOrDefault(key, 0) + 1;
        orderMapping.put(key, next);
        return next;
    }

    public String getTaskId() {
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
        }
        return taskId;
    }

    public String renewTaskId() {
        taskOrder.set(1);
        taskId = UUID.randomUUID().toString();
        return taskId;
    }
}
