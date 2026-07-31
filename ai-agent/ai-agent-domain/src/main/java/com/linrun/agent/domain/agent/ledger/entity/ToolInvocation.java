package com.linrun.agent.domain.agent.ledger.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单次工具调用账本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocation {

    private Long id;

    /** 所属 run */
    private Long runId;

    /** 来源 LLM 调用 */
    private Long llmInvocationId;

    /** 模型返回的 toolCallId */
    private String toolCallId;

    /** 原始分发顺序 */
    private Integer dispatchIndex;

    /** 当前 agent 名称 */
    private String agentName;

    /** 当前步号 */
    private Integer stepNo;

    /** 工具名称 */
    private String toolName;

    /** local / mcp */
    private String toolProvider;

    /** Stable canonical input hash used to de-duplicate durable work within a run. */
    private String operationKey;

    /** EXECUTED or REUSED. */
    private String executionMode;

    /** Original successful invocation reused by this row, if any. */
    private Long sourceInvocationId;

    /** Durable worker lifecycle projection. */
    private String durableStatus;

    /** Run fence copied to the worker and checked by callbacks. */
    private Long durableFencingToken;

    /** 入参 JSON */
    private String inputJson;

    /** 主智能体 observation */
    private String llmObservation;

    /** 面向用户与历史回放的原始工具结果 */
    private String toolResult;

    /** 状态 */
    private Integer status;

    /** 错误信息 */
    private String errorMsg;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 耗时 */
    private Long durationMs;

    /** Durable worker lease deadline, used only by recovery/reconcile. */
    private LocalDateTime durableLeaseExpiresAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
