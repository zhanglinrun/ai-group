package org.wwz.ai.domain.agent.checkpoint;

import java.util.Optional;

/**
 * Plan-Solve checkpoint 持久化端口。
 */
public interface PlanCheckpointRepository {

    PlanExecutionCheckpoint save(PlanExecutionCheckpoint checkpoint);

    Optional<PlanExecutionCheckpoint> findOwned(String checkpointId, String ownerId, String sessionId);

    /**
     * 原子认领一次显式恢复。相同 requestId 可幂等重试，不同 requestId 不得重复消费。
     */
    boolean claimForResume(String checkpointId,
                           String ownerId,
                           String sessionId,
                           String resumedByRequestId,
                           PlanResumeDecision decision);

    /** 成功完成后关闭该 run 的全部 checkpoint。 */
    void markRunCompleted(Long runId);
}
