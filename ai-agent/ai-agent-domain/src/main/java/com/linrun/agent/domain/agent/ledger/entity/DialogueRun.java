package com.linrun.agent.domain.agent.ledger.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单次对话执行总账。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DialogueRun {

    private Long id;

    /** 对外稳定运行标识，首期直接复用 requestId */
    private String runUid;

    /** 单次请求ID */
    private String requestId;

    /** 会话ID */
    private String sessionId;

    /** 登录用户 ID（ownerId = userId） */
    private String ownerId;

    /** 入口执行链 agent_loop:{standard|auto|deep}；更早的只读值仅由 replay 兼容层解释。 */
    private String entryAgent;

    /** 本轮选中的固定角色 ID，与 entryAgent 的运行模式语义独立 */
    private String roleAgentId;

    /** 本轮启动时解析出的固定角色名称快照 */
    private String roleAgentName;

    /** 运行状态 */
    private Integer status;

    /** 用户原始问题 */
    private String queryText;

    /** 客户端请求稳定指纹；旧历史行允许为空，仅用于只读回放兼容。 */
    private String requestFingerprint;

    /** 最终总结文本 */
    private String finalSummaryText;

    /** LLM 调用次数 */
    private Integer llmCallCount;

    /** 工具调用次数 */
    private Integer toolCallCount;

    /** 产物数量 */
    private Integer artifactCount;

    /** LLM 输入 token 总量 */
    private Integer promptTokensTotal;

    /** LLM 输出 token 总量 */
    private Integer completionTokensTotal;

    /** LLM token 总量 */
    private Integer totalTokensTotal;

    /** 失败码 */
    private String errorCode;

    /** 失败信息 */
    private String errorMsg;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 本轮允许执行到的绝对截止时间。 */
    private LocalDateTime deadlineAt;

    /** 当前执行进程最后一次持久化心跳。 */
    private LocalDateTime heartbeatAt;

    /** 当前持有 lease 的 worker；仅该 worker 可续租或写入终态。 */
    private String ownerWorkerId;

    /** 当前 worker lease 的绝对失效时间。 */
    private LocalDateTime leaseExpiresAt;

    /** 单调 fencing token，传递至 tool/checkpoint/outbox 边界。 */
    private Long fencingToken;

    /** 乐观版本，保留给外部 durable state 的 CAS 关联。 */
    private Long version;

    /** 用户显式取消的 durable intent 时间。 */
    private LocalDateTime cancelRequestedAt;

    /** 请求取消的登录用户。 */
    private String cancelRequestedBy;

    /** 首个 durable terminal transition 的时间。 */
    private LocalDateTime terminalAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 总耗时 */
    private Long durationMs;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
