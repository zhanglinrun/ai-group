package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * run 级运行态上下文。
 * 需要兼容 PlanSolve 并发 executor，因此当前 agent / step / llm invocation 采用线程内视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunState {

    private Long runId;

    private String runUid;

    @Builder.Default
    @ToString.Exclude
    private AtomicInteger nextLlmInvocationSeq = new AtomicInteger(1);

    @Builder.Default
    @ToString.Exclude
    private ConcurrentMap<String, Long> toolInvocationIdByToolCallId = new ConcurrentHashMap<>();

    /**
     * run 级失败标记。
     * agent 失败分支通常吞掉异常后降级返回（不向上抛），该标记让 run 收口时能写入真实终态。
     */
    @Builder.Default
    @ToString.Exclude
    private AtomicBoolean runFailedFlag = new AtomicBoolean(false);

    @Builder.Default
    @ToString.Exclude
    private transient ThreadLocal<String> currentAgentNameHolder = new ThreadLocal<>();

    @Builder.Default
    @ToString.Exclude
    private transient ThreadLocal<Integer> currentStepNoHolder = new ThreadLocal<>();

    @Builder.Default
    @ToString.Exclude
    private transient ThreadLocal<Long> currentLlmInvocationIdHolder = new ThreadLocal<>();

    /**
     * 申请下一个全局递增的 LLM 顺序号。
     */
    public int nextInvocationSeq() {
        return nextLlmInvocationSeq.getAndIncrement();
    }

    /**
     * 标记本次 run 内发生过被捕获吞掉的执行失败。
     */
    public void markRunFailed() {
        runFailedFlag.set(true);
    }

    /**
     * 本次 run 是否发生过被捕获吞掉的执行失败。
     */
    public boolean isRunFailed() {
        return runFailedFlag.get();
    }

    /**
     * 标记当前线程的执行位置。
     */
    public void markExecutionPosition(String agentName, Integer stepNo) {
        currentAgentNameHolder.set(agentName);
        currentStepNoHolder.set(stepNo);
    }

    /**
     * 绑定当前线程的 LLM invocation。
     */
    public void bindCurrentLlmInvocationId(Long llmInvocationId) {
        currentLlmInvocationIdHolder.set(llmInvocationId);
    }

    /**
     * 清理当前线程的 LLM invocation 视图。
     */
    public void clearCurrentLlmInvocationId() {
        currentLlmInvocationIdHolder.remove();
    }

    /**
     * 合并 toolCallId 到 invocationId 的映射。
     */
    public void bindToolInvocationIds(Map<String, Long> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return;
        }
        toolInvocationIdByToolCallId.putAll(mapping);
    }

    /**
     * 读取指定 toolCallId 的账本ID。
     */
    public Long resolveToolInvocationId(String toolCallId) {
        return toolCallId == null ? null : toolInvocationIdByToolCallId.get(toolCallId);
    }

    public String getCurrentAgentName() {
        return currentAgentNameHolder.get();
    }

    public Integer getCurrentStepNo() {
        return currentStepNoHolder.get();
    }

    public Long getCurrentLlmInvocationId() {
        return currentLlmInvocationIdHolder.get();
    }
}
