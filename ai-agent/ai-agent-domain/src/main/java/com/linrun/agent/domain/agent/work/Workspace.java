package com.linrun.agent.domain.agent.work;

import java.time.Instant;
import java.util.Map;

/** ChatGPT Work/Project 风格的项目工作区。会话、任务与工具策略均以 workspace 为边界。 */
public record Workspace(
        String id,
        String ownerId,
        String name,
        String instructions,
        Map<String, Object> toolPolicy,
        Instant createdAt,
        Instant updatedAt
) {
    public Workspace {
        toolPolicy = toolPolicy == null ? Map.of() : Map.copyOf(toolPolicy);
    }
}
