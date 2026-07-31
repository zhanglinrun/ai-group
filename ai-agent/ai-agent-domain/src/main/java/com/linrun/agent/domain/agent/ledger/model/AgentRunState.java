package com.linrun.agent.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import com.linrun.agent.domain.agent.runtime.observability.AgentTraceScope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;

/**
 * run 级运行态上下文。
 * Agent Loop 的 run-local execution state.
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

    /** Explicitly named tools whose run-level single-use budget has already been consumed. */
    @Builder.Default
    @ToString.Exclude
    private ConcurrentMap<String, Boolean> satisfiedSingleUseToolNames = new ConcurrentHashMap<>();

    /** tool_search 已发现并授权本次 run 通过 execute_extra_tool 调用的 canonical 工具名称。 */
    @Builder.Default
    @ToString.Exclude
    private ConcurrentMap<String, Boolean> discoveredToolNames = new ConcurrentHashMap<>();

    /**
     * Definition hashes pinned by tool_search. Empty legacy discoveries remain
     * compatible; new discoveries must match before their native schema is
     * exposed on a later model turn.
     */
    @Builder.Default
    @ToString.Exclude
    private ConcurrentMap<String, String> discoveredToolDefinitionHashes = new ConcurrentHashMap<>();

    /** Run-local skill definition pins. A changed Skill may not silently replace loaded instructions. */
    @Builder.Default
    @ToString.Exclude
    private ConcurrentMap<String, String> activatedSkillDefinitionHashes = new ConcurrentHashMap<>();

    @Builder.Default
    @ToString.Exclude
    private AtomicInteger latestToolCatalogCount = new AtomicInteger();

    @Builder.Default
    @ToString.Exclude
    private AtomicInteger latestExposedToolCount = new AtomicInteger();

    @Builder.Default
    @ToString.Exclude
    private AtomicInteger latestDeferredToolCount = new AtomicInteger();

    @Builder.Default
    @ToString.Exclude
    private AtomicInteger latestToolSchemaChars = new AtomicInteger();

    /**
     * run 级失败标记。
     * agent 失败分支通常吞掉异常后降级返回（不向上抛），该标记让 run 收口时能写入真实终态。
     */
    @Builder.Default
    @ToString.Exclude
    private AtomicBoolean runFailedFlag = new AtomicBoolean(false);

    /** CompletionGate attempts made by the unified loop. */
    @Builder.Default
    @ToString.Exclude
    private AtomicInteger completionAttemptCount = new AtomicInteger();

    /** Completion attempts rejected with corrective actions. */
    @Builder.Default
    @ToString.Exclude
    private AtomicInteger completionBlockedCount = new AtomicInteger();

    /** Number of independent final verifier executions. */
    @Builder.Default
    @ToString.Exclude
    private AtomicInteger finalVerifierCount = new AtomicInteger();

    /** Run-local usage counters used by AgentRunBudget and terminal metrics. */
    @Builder.Default
    @ToString.Exclude
    private AtomicLong totalTokenCount = new AtomicLong();

    @Builder.Default
    @ToString.Exclude
    private AtomicLong chargedMicrocredits = new AtomicLong();

    @Builder.Default
    @ToString.Exclude
    private AtomicInteger toolCallCount = new AtomicInteger();

    /** Monotonic safe-context projection revision; prompt text itself is never traced. */
    @Builder.Default
    @ToString.Exclude
    private AtomicInteger contextRevision = new AtomicInteger();

    /** Number of typed execution-evidence records produced in this run. */
    @Builder.Default
    @ToString.Exclude
    private AtomicInteger evidenceCount = new AtomicInteger();

    /** The trace id is a correlation value only; Ledger remains the fact source. */
    private String traceId;

    @ToString.Exclude
    private transient AgentTraceScope sessionTraceScope;

    @ToString.Exclude
    private transient AgentTraceScope runTraceScope;

    @Builder.Default
    @ToString.Exclude
    private transient ConcurrentMap<Long, AgentTraceScope> llmTraceScopeByInvocationId = new ConcurrentHashMap<>();

    @Builder.Default
    @ToString.Exclude
    private transient ConcurrentMap<String, AgentTraceScope> toolTraceScopeByToolCallId = new ConcurrentHashMap<>();

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

    public void recordCompletionAttempt(boolean accepted, boolean verifierExecuted) {
        completionAttemptCount.incrementAndGet();
        if (!accepted) {
            completionBlockedCount.incrementAndGet();
        }
        if (verifierExecuted) {
            finalVerifierCount.incrementAndGet();
        }
    }

    public int getCompletionAttemptCountValue() {
        return completionAttemptCount.get();
    }

    public int getCompletionBlockedCountValue() {
        return completionBlockedCount.get();
    }

    public int getFinalVerifierCountValue() {
        return finalVerifierCount.get();
    }

    public void recordLlmUsage(long totalTokens, long microcredits) {
        if (totalTokens > 0) {
            totalTokenCount.addAndGet(totalTokens);
        }
        recordChargedMicrocredits(microcredits);
    }

    public void recordChargedMicrocredits(long microcredits) {
        if (microcredits > 0) {
            chargedMicrocredits.addAndGet(microcredits);
        }
    }

    public void recordToolCalls(int count) {
        if (count > 0) {
            toolCallCount.addAndGet(count);
        }
    }

    public long getTotalTokenCountValue() {
        return totalTokenCount.get();
    }

    public long getChargedMicrocreditsValue() {
        return chargedMicrocredits.get();
    }

    public int getToolCallCountValue() {
        return toolCallCount.get();
    }

    public int nextContextRevision() {
        return contextRevision.incrementAndGet();
    }

    public int getContextRevisionValue() {
        return contextRevision.get();
    }

    public void recordEvidenceCount(int count) {
        evidenceCount.set(Math.max(0, count));
    }

    public int getEvidenceCountValue() {
        return evidenceCount.get();
    }

    public void activateTrace(AgentTraceScope sessionScope, AgentTraceScope runScope) {
        this.sessionTraceScope = sessionScope;
        this.runTraceScope = runScope;
        this.traceId = runScope == null ? null : runScope.traceId();
    }

    public void bindLlmTraceScope(Long invocationId, AgentTraceScope scope) {
        if (invocationId != null && scope != null) {
            llmTraceScopeByInvocationId.put(invocationId, scope);
        }
    }

    public AgentTraceScope resolveLlmTraceScope(Long invocationId) {
        return invocationId == null ? null : llmTraceScopeByInvocationId.get(invocationId);
    }

    public void bindToolTraceScope(String toolCallId, AgentTraceScope scope) {
        if (toolCallId != null && !toolCallId.isBlank() && scope != null) {
            toolTraceScopeByToolCallId.put(toolCallId, scope);
        }
    }

    public AgentTraceScope resolveToolTraceScope(String toolCallId) {
        return toolCallId == null ? null : toolTraceScopeByToolCallId.get(toolCallId);
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

    /** Atomically consumes an explicitly requested single-use tool budget. */
    public boolean tryConsumeSingleUseTool(String toolName) {
        return toolName != null
                && !toolName.isBlank()
                && satisfiedSingleUseToolNames.putIfAbsent(toolName, Boolean.TRUE) == null;
    }

    public void markToolsDiscovered(Iterable<String> toolNames) {
        if (toolNames == null) {
            return;
        }
        for (String toolName : toolNames) {
            if (toolName != null && !toolName.isBlank()) {
                discoveredToolNames.put(toolName, Boolean.TRUE);
            }
        }
    }

    public void markToolsDiscovered(Map<String, String> toolDefinitionHashes) {
        if (toolDefinitionHashes == null || toolDefinitionHashes.isEmpty()) {
            return;
        }
        markToolsDiscovered(toolDefinitionHashes.keySet());
        toolDefinitionHashes.forEach((toolName, definitionHash) -> {
            if (toolName != null && !toolName.isBlank() && definitionHash != null && !definitionHash.isBlank()) {
                discoveredToolDefinitionHashes.putIfAbsent(toolName, definitionHash);
            }
        });
    }

    public Set<String> discoveredToolNamesSnapshot() {
        return Set.copyOf(discoveredToolNames.keySet());
    }

    public Map<String, String> discoveredToolDefinitionHashesSnapshot() {
        return Map.copyOf(discoveredToolDefinitionHashes);
    }

    /**
     * Pin a Skill body when it is first loaded into a model turn. There are no
     * automatic body loads in the current runtime, so P60 permits at most one
     * explicit mid-run addition; repeated reads of the same pinned version are
     * allowed and a changed version fails closed.
     */
    public synchronized SkillDefinitionPinResult pinSkillDefinition(String skillName,
                                                                      String definitionHash,
                                                                      int maxDistinctSkills) {
        if (skillName == null || skillName.isBlank() || definitionHash == null || definitionHash.isBlank()) {
            return SkillDefinitionPinResult.INVALID;
        }
        String existing = activatedSkillDefinitionHashes.get(skillName);
        if (existing != null) {
            return existing.equals(definitionHash)
                    ? SkillDefinitionPinResult.PINNED
                    : SkillDefinitionPinResult.VERSION_CHANGED;
        }
        if (activatedSkillDefinitionHashes.size() >= Math.max(0, maxDistinctSkills)) {
            return SkillDefinitionPinResult.LIMIT_REACHED;
        }
        activatedSkillDefinitionHashes.put(skillName, definitionHash);
        return SkillDefinitionPinResult.PINNED;
    }

    public Map<String, String> activatedSkillDefinitionHashesSnapshot() {
        return Map.copyOf(activatedSkillDefinitionHashes);
    }

    public enum SkillDefinitionPinResult {
        PINNED,
        VERSION_CHANGED,
        LIMIT_REACHED,
        INVALID
    }

    public void recordToolExposure(int catalogCount, int exposedCount, int deferredCount, int schemaChars) {
        latestToolCatalogCount.set(Math.max(0, catalogCount));
        latestExposedToolCount.set(Math.max(0, exposedCount));
        latestDeferredToolCount.set(Math.max(0, deferredCount));
        latestToolSchemaChars.set(Math.max(0, schemaChars));
    }

    public int getLatestToolCatalogCountValue() {
        return latestToolCatalogCount.get();
    }

    public int getLatestExposedToolCountValue() {
        return latestExposedToolCount.get();
    }

    public int getLatestDeferredToolCountValue() {
        return latestDeferredToolCount.get();
    }

    public int getLatestToolSchemaCharsValue() {
        return latestToolSchemaChars.get();
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
