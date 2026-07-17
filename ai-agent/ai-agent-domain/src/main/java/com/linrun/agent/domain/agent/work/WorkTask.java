package com.linrun.agent.domain.agent.work;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Claude Code 风格的共享任务白板条目。它描述 work item，不等同于运行中的 Agent task。 */
public record WorkTask(
        String id,
        String workspaceId,
        String ownerId,
        String subject,
        String description,
        String activeForm,
        Status status,
        String taskOwner,
        List<String> blocks,
        List<String> blockedBy,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        long version
) {
    public enum Status { PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED }

    public WorkTask {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean isUnblocked() {
        return status == Status.PENDING && blockedBy.isEmpty();
    }
}
