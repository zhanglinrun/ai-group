package com.linrun.agent.domain.agent.runtime.hitl;

/**
 * HITL 审批决策（6 种）。
 *
 * <p>对应前端弹窗的 6 种用户响应，每种决策决定 ToolDispatcher 的后续行为。</p>
 */
public enum ApprovalDecision {

    /** Waiting for an online user decision. */
    PENDING,

    /** 批准本次 */
    APPROVED,

    /** 批准本工具的所有后续调用（按工具维度缓存，避免重复问） */
    APPROVED_ALL,

    /** 拒绝，跳过该工具调用 */
    REJECTED,

    /** 修改参数后执行（用户改了 arguments） */
    MODIFIED,

    /** 跳过本次（不执行也不报错，返回空结果） */
    SKIPPED,

    /** 超时未响应，按拒绝处理 */
    TIMEOUT
}
