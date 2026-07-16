package org.wwz.ai.domain.agent.checkpoint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Plan-Solve checkpoint 聚合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanExecutionCheckpoint {

    private Long id;
    private String checkpointId;
    private Long runId;
    private String requestId;
    private String sessionId;
    private String ownerId;
    private Integer sequenceNo;
    private PlanCheckpointPhase phase;
    private Integer stepIndex;
    private PlanCheckpointState state;
    private String snapshotHash;
    private Boolean resumable;
    private String resumedByRequestId;
    private PlanResumeDecision resumeDecision;
    private LocalDateTime resumedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
