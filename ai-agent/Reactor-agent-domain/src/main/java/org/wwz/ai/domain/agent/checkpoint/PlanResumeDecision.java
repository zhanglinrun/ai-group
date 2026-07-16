package org.wwz.ai.domain.agent.checkpoint;

/**
 * 用户对 checkpoint 重放风险的决定。
 */
public enum PlanResumeDecision {

    /** 仅在 checkpoint 后没有未知副作用工具时恢复。 */
    SAFE_ONLY,

    /** 用户明确同意从安全点重新执行可能已开始的步骤。 */
    RESTART_FROM_CHECKPOINT;

    public static PlanResumeDecision fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return SAFE_ONLY;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported resume decision: " + value, exception);
        }
    }
}
