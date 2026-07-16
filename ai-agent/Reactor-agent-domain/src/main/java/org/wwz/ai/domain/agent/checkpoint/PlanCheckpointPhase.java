package org.wwz.ai.domain.agent.checkpoint;

/**
 * Plan-Solve 可恢复安全点。
 *
 * <p>安全点只描述可以重建的业务状态，不序列化 Agent、Spring Bean、流或密钥。</p>
 */
public enum PlanCheckpointPhase {

    /** 已持久化计划，下一任务尚未开始。 */
    READY_FOR_STEP,

    /** 全部计划步骤已完成，最终总结尚未生成。 */
    BEFORE_SUMMARY
}
