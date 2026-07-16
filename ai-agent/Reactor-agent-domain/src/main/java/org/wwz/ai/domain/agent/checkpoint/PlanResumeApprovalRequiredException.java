package org.wwz.ai.domain.agent.checkpoint;

import java.util.List;

/**
 * checkpoint 后存在未知副作用，自动恢复必须停下来等待用户决定。
 */
public class PlanResumeApprovalRequiredException extends IllegalStateException {

    private final String checkpointId;
    private final List<String> ambiguousTools;

    public PlanResumeApprovalRequiredException(String checkpointId, List<String> ambiguousTools) {
        super("Checkpoint " + checkpointId
                + " has ambiguous replay facts " + ambiguousTools
                + ". Retry with resumeDecision=RESTART_FROM_CHECKPOINT only after user approval.");
        this.checkpointId = checkpointId;
        this.ambiguousTools = List.copyOf(ambiguousTools);
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public List<String> getAmbiguousTools() {
        return ambiguousTools;
    }
}
