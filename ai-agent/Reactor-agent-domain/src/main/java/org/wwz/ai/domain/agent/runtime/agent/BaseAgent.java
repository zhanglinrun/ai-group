package org.wwz.ai.domain.agent.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactFormatter;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.dto.Memory;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;
import org.wwz.ai.domain.agent.runtime.llm.LLM;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;
import org.wwz.ai.domain.agent.ledger.model.ArtifactRecordCommand;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 所有 Agent 的抽象基类。
 * 固定执行主循环，并统一承接记忆管理、工具执行和执行账本接入。
 */
@Slf4j
@Data
@Accessors(chain = true)
public abstract class BaseAgent {

    /** Agent 名称 */
    private String name;
    /** Agent 描述 */
    private String description;
    /** 系统提示词 */
    private String systemPrompt;
    /** 下一步提示词 */
    private String nextStepPrompt;
    /** 当前 Agent 可用工具集合 */
    public ToolCollection availableTools = new ToolCollection();
    /** Agent 记忆 */
    private Memory memory = new Memory();
    /** LLM 门面 */
    protected LLM llm;
    /** Agent 上下文 */
    protected AgentContext context;

    /** 当前状态 */
    private AgentState state = AgentState.IDLE;
    /** 最大步数 */
    private int maxSteps = 10;
    /** 当前步号 */
    private int currentStep = 0;
    /** 死循环检测阈值：连续产出相同非空步骤结果达到该次数即判定卡壳并提前收敛（<=0 关闭） */
    private int duplicateThreshold = 2;

    /** 死循环检测：上一步结果签名 */
    private String lastStepSignature;
    /** 死循环检测：连续重复计数 */
    private int repeatedStepCount = 0;

    /** 单个工具调用最大尝试次数（含首次）：>1 时对抛异常的瞬时失败有界重试。默认 1（不重试）。 */
    private int toolMaxAttempts = 1;

    /** 达到最大步数的终止标识（可识别，供上层区分正常完成与兜底终止） */
    public static final String TERMINATION_MAX_STEPS = "Terminated: Reached max steps";
    /** 检测到死循环的终止标识 */
    public static final String TERMINATION_STUCK = "Terminated: Detected repeated steps, stopping to avoid a dead loop";
    /** think 返回 false 时 step 的占位结果，不作为死循环信号 */
    private static final String THINKING_COMPLETE_MARKER = "Thinking complete - no action needed";

    /** 输出器 */
    Printer printer;

    /** 数字员工提示词 */
    private String digitalEmployeePrompt;

    /**
     * 子类定义单步执行逻辑。
     */
    public abstract String step();

    /**
     * Agent 主循环。
     */
    public String run(String query) {
        setState(AgentState.IDLE);

        if (query != null && !query.isEmpty()) {
            updateMemory(RoleType.USER, query, null);
        }

        // 每次 run 独立计数，避免跨 run 误判死循环
        lastStepSignature = null;
        repeatedStepCount = 0;

        List<String> results = new ArrayList<>();
        try {
            while (currentStep < maxSteps && state != AgentState.FINISHED) {
                // 客户端断开（SSE 关闭）时在步骤边界终止，避免继续发起调用；
                // 标记 run 失败供审计，当前在途 LLM 仍由调用级结算处理。
                if (isDownstreamAborted()) {
                    log.info("{} {} downstream aborted, stop agent loop at step {}",
                            context == null ? null : context.getRequestId(), getName(), currentStep);
                    if (context != null) {
                        context.markRunFailed();
                    }
                    state = AgentState.IDLE;
                    break;
                }
                currentStep++;
                if (context != null) {
                    // 每步进入前都刷新一次当前位置，供 LLM / tool 账本读取。
                    context.markExecutionPosition(getName(), currentStep);
                }
                log.info("{} {} Executing step {}/{}", context == null ? null : context.getRequestId(), getName(), currentStep, maxSteps);
                String stepResult = step();
                results.add(stepResult);

                // 死循环兜底：连续多步产出完全相同的非空结果（模型反复调用同工具拿到同观察）即判定卡壳，
                // 提前以与 maxSteps 一致的终止语义（IDLE）收敛，避免白烧 token 到步数上限。
                if (state != AgentState.FINISHED && isRepeatedStep(stepResult)) {
                    log.warn("{} {} detected {} repeated steps, breaking to avoid dead loop",
                            context == null ? null : context.getRequestId(), getName(), repeatedStepCount);
                    results.add(TERMINATION_STUCK);
                    currentStep = 0;
                    state = AgentState.IDLE;
                    break;
                }
            }

            if (currentStep >= maxSteps) {
                currentStep = 0;
                state = AgentState.IDLE;
                results.add(TERMINATION_MAX_STEPS + " (" + maxSteps + ")");
            }
        } catch (Exception e) {
            state = AgentState.ERROR;
            throw e;
        }

        return results.isEmpty() ? "No steps executed" : results.get(results.size() - 1);
    }

    /**
     * 下游是否已断开：经 Printer 抽象感知 SSE 断开，domain 无需依赖协议实现。
     */
    private boolean isDownstreamAborted() {
        return printer != null && printer.isAborted();
    }

    /**
     * 死循环检测：仅当连续出现相同且非空（且非"无需行动"占位）的步骤结果达到阈值时返回 true。
     * think 返回 false 走的是 FINISHED 正常收尾路径，不会进入此判定。
     */
    private boolean isRepeatedStep(String stepResult) {
        if (duplicateThreshold <= 0) {
            return false;
        }
        String signature = stepResult == null ? "" : stepResult.trim();
        if (signature.isEmpty() || THINKING_COMPLETE_MARKER.equals(signature)) {
            lastStepSignature = null;
            repeatedStepCount = 0;
            return false;
        }
        if (signature.equals(lastStepSignature)) {
            repeatedStepCount++;
        } else {
            lastStepSignature = signature;
            repeatedStepCount = 0;
        }
        return repeatedStepCount >= duplicateThreshold;
    }

    /**
     * 追加记忆消息。
     */
    public void updateMemory(RoleType role, String content, String base64Image, Object... args) {
        Message message;
        switch (role) {
            case USER:
                message = Message.userMessage(content, base64Image);
                break;
            case SYSTEM:
                message = Message.systemMessage(content, base64Image);
                break;
            case ASSISTANT:
                message = Message.assistantMessage(content, base64Image);
                break;
            case TOOL:
                message = Message.toolMessage(content, (String) args[0], base64Image);
                break;
            default:
                throw new IllegalArgumentException("Unsupported role type: " + role);
        }
        memory.addMessage(message);
    }

    /**
     * 预装历史消息，避免共享同一份可变列表。
     */
    protected void preloadMemory(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        memory.addMessages(new ArrayList<>(messages));
    }

    /**
     * 注入会话历史摘要。
     */
    protected String injectHistoryDialogue(String promptTemplate, String historyDialogue) {
        String normalizedTemplate = promptTemplate == null ? "" : promptTemplate;
        String normalizedHistory = historyDialogue == null ? "" : historyDialogue;
        if (normalizedTemplate.contains("{{history_dialogue}}")) {
            return normalizedTemplate.replace("{{history_dialogue}}", normalizedHistory);
        }
        if (normalizedHistory.isBlank()) {
            return normalizedTemplate;
        }
        return normalizedTemplate + "\n\n## 用户历史对话信息\n<history_dialogue>\n"
                + normalizedHistory
                + "\n</history_dialogue>";
    }

    /**
     * 从工具集合构建工具提示词。
     */
    protected String buildToolPrompt(ToolCollection tools) {
        if (tools == null || tools.getToolMap() == null || tools.getToolMap().isEmpty()) {
            return "";
        }
        StringBuilder toolPrompt = new StringBuilder();
        for (BaseTool tool : tools.getToolMap().values()) {
            toolPrompt.append(String.format("工具名：%s 工具描述：%s\n", tool.getName(), tool.getDescription()));
        }
        return toolPrompt.toString();
    }

    /**
     * 初始化系统提示词与下一步提示词。
     */
    protected void initializePrompts(Map<String, String> systemPromptMap,
                                     Map<String, String> nextStepPromptMap,
                                     String defaultSystemPrompt,
                                     String defaultNextStepPrompt,
                                     String toolPrompt,
                                     String extraPlaceholder,
                                     String extraValue) {
        String promptKey = "default";
        String nextPromptKey = "default";

        String systemTemplate = systemPromptMap.getOrDefault(promptKey, defaultSystemPrompt)
                .replace("{{tools}}", toolPrompt)
                .replace("{{query}}", context.getQuery())
                .replace("{{date}}", context.getDateInfo())
                .replace("{{basePrompt}}", context.getBasePrompt());
        if (extraPlaceholder != null) {
            systemTemplate = systemTemplate.replace(extraPlaceholder, extraValue);
        }
        setSystemPrompt(injectHistoryDialogue(systemTemplate, context.getHistoryDialogue()));

        String nextTemplate = nextStepPromptMap.getOrDefault(nextPromptKey, defaultNextStepPrompt)
                .replace("{{tools}}", toolPrompt)
                .replace("{{query}}", context.getQuery())
                .replace("{{date}}", context.getDateInfo())
                .replace("{{basePrompt}}", context.getBasePrompt());
        if (extraPlaceholder != null) {
            nextTemplate = nextTemplate.replace(extraPlaceholder, extraValue);
        }
        setNextStepPrompt(injectHistoryDialogue(nextTemplate, context.getHistoryDialogue()));
    }

    /**
     * 初始化系统提示词与下一步提示词。
     * 仅在 systemPrompt 中注入历史上下文，避免 nextStepPrompt 进入记忆后重复放大会话历史。
     */
    protected void initializePromptsWithHistoryOnlyInSystem(Map<String, String> systemPromptMap,
                                                            Map<String, String> nextStepPromptMap,
                                                            String defaultSystemPrompt,
                                                            String defaultNextStepPrompt,
                                                            String toolPrompt,
                                                            String extraPlaceholder,
                                                            String extraValue) {
        String promptKey = "default";
        String nextPromptKey = "default";

        String systemTemplate = systemPromptMap.getOrDefault(promptKey, defaultSystemPrompt)
                .replace("{{tools}}", toolPrompt)
                .replace("{{query}}", context.getQuery())
                .replace("{{date}}", context.getDateInfo())
                .replace("{{basePrompt}}", context.getBasePrompt());
        if (extraPlaceholder != null) {
            systemTemplate = systemTemplate.replace(extraPlaceholder, extraValue);
        }
        setSystemPrompt(injectHistoryDialogue(systemTemplate, context.getHistoryDialogue()));

        String nextTemplate = nextStepPromptMap.getOrDefault(nextPromptKey, defaultNextStepPrompt)
                .replace("{{tools}}", toolPrompt)
                .replace("{{query}}", context.getQuery())
                .replace("{{date}}", context.getDateInfo())
                .replace("{{basePrompt}}", context.getBasePrompt());
        if (extraPlaceholder != null) {
            nextTemplate = nextTemplate.replace(extraPlaceholder, extraValue);
        }
        setNextStepPrompt(nextTemplate);
    }

    /**
     * 为单次工具结果追加当前 toolCall 的文件摘要。
     */
    protected String attachToolArtifactSummary(String result, String toolCallId) {
        if (context == null || StringUtils.isBlank(toolCallId)) {
            return result;
        }
        return ToolArtifactFormatter.appendToolArtifactSummary(
                result,
                context.getArtifactBindingsByToolCallId(toolCallId)
        );
    }

    /**
     * 子类按需覆写 observation 最大长度。
     * BaseAgent 本身不关心具体配置来源，只负责统一收口规则。
     */
    protected Integer resolveMaxObserveLength() {
        return null;
    }

    /**
     * 统一生成最终 observation。
     * 先做长度裁剪，再追加当前 toolCall 关联的产物摘要，确保账本与主智能体看到的内容完全一致。
     */
    protected String buildFinalLlmObservation(String rawObservation, String toolCallId) {
        String observation = StringUtils.defaultString(rawObservation);
        Integer maxObserve = resolveMaxObserveLength();
        if (maxObserve != null && maxObserve > 0 && observation.length() > maxObserve) {
            observation = observation.substring(0, maxObserve);
        }
        return attachToolArtifactSummary(observation, toolCallId);
    }

    /**
     * 把工具最终 observation 写回记忆。
     * 无论单工具还是批量工具，都统一走这一条链路，避免不同 Agent 各自拼装结果。
     */
    protected String writeToolObservationToMemory(ToolCall command, ToolExecutionOutcome outcome) {
        String observation = outcome == null ? "" : StringUtils.defaultString(outcome.getLlmObservation());
        if (command == null) {
            return observation;
        }
        if ("struct_parse".equals(llm.getFunctionCallType())) {
            String content = getMemory().getLastMessage().getContent();
            getMemory().getLastMessage().setContent(content + "\n 工具执行结果为:\n" + observation);
            return observation;
        }
        getMemory().addMessage(Message.toolMessage(observation, command.getId(), null));
        return observation;
    }

    /**
     * 对外保留原有工具执行契约。
     */
    public String executeTool(ToolCall command) {
        return executeToolOutcome(command).getLlmObservation();
    }

    /**
     * 单工具路径的完整执行结果。
     * 包含预登记、执行、observation 收口、账本落库与产物登记。
     */
    protected ToolExecutionOutcome executeToolOutcome(ToolCall command) {
        List<ToolCall> commands = command == null ? List.of() : List.of(command);
        Map<String, Long> toolInvocationIds = ensureToolInvocationIds(commands);
        if (context != null && context.getAgentRunState() != null && !toolInvocationIds.isEmpty()) {
            context.getAgentRunState().bindToolInvocationIds(toolInvocationIds);
        }
        Map<String, Integer> dispatchIndexMapping = buildDispatchIndexMapping(commands);
        emitToolCallRunningEvents(commands, dispatchIndexMapping);
        ToolExecutionOutcome outcome = finalizeToolExecutionOutcome(command, executeToolInternal(command));
        finishToolInvocation(command, outcome);
        recordToolArtifacts(command);
        emitToolCallFinishedEvent(command, dispatchIndexMapping.get(command == null ? null : command.getId()), outcome);
        return outcome;
    }

    /**
     * 内部工具执行，保留账本需要的状态与结构化输出。
     */
    private ToolExecutionOutcome executeToolInternal(ToolCall command) {
        if (command == null || command.getFunction() == null
                || StringUtils.isBlank(command.getFunction().getName())) {
            return ToolExecutionOutcome.failure(
                    "Error: Invalid function call format",
                    "Error: Invalid function call format",
                    null,
                    "Invalid function call format"
            );
        }

        String toolName = command.getFunction().getName();
        ObjectMapper mapper = new ObjectMapper();
        Object args;
        try {
            args = mapper.readValue(normalizeToolPayload(command.getFunction().getArguments()), Object.class);
        } catch (Exception parseEx) {
            // 参数校验：入参不是合法 JSON 时，给出结构化纠错反馈让模型修正参数，而不是笼统报错。
            String rawArguments = command.getFunction().getArguments();
            log.warn("{} tool arguments parse failed tool={} argsChars={} errorType={}",
                    context.getRequestId(), toolName,
                    rawArguments == null ? 0 : rawArguments.length(), parseEx.getClass().getSimpleName());
            String msg = "工具 " + toolName + " 参数解析失败：入参必须为合法 JSON，请检查后用正确参数重试。";
            return ToolExecutionOutcome.failure(msg, msg, null, "invalid tool arguments: " + parseEx.getMessage());
        }

        int attempts = Math.max(1, toolMaxAttempts);
        Exception lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long attemptStartedAt = System.nanoTime();
            try {
                ToolArtifactSource artifactSource = ToolArtifactSource.builder()
                        .sessionId(context.getSessionId())
                        .requestId(context.getRequestId())
                        .toolCallId(command.getId())
                        .toolName(toolName)
                        .build();

                Object resultObject;
                context.bindCurrentToolArtifactSource(artifactSource);
                try {
                    resultObject = availableTools.execute(toolName, args);
                } finally {
                    context.clearCurrentToolArtifactSource();
                }

                log.info("{} execute tool completed tool={} attempt={} resultType={} durationMs={}",
                        context.getRequestId(), toolName, attempt,
                        resultObject == null ? "null" : resultObject.getClass().getSimpleName(),
                        (System.nanoTime() - attemptStartedAt) / 1_000_000L);

                if (resultObject == null) {
                    // 返回空视为确定性失败，不重试；保持既有可识别文案。
                    return ToolExecutionOutcome.failure(
                            "Tool " + toolName + " Error.",
                            "Tool " + toolName + " Error.",
                            null,
                            "Tool returned null"
                    );
                }

                ToolResultPayload payload = normalizeToolResultPayload(resultObject, mapper);
                String toolResult = StringUtils.defaultString(payload.getToolResult());
                String llmObservation = StringUtils.defaultIfBlank(payload.getLlmObservation(), toolResult);
                if (Boolean.TRUE.equals(payload.getFailed())) {
                    // 业务级失败是确定性的，不重试，保留工具自身的结果语义。
                    return ToolExecutionOutcome.failure(
                            toolResult,
                            llmObservation,
                            payload.getStructuredOutput(),
                            StringUtils.defaultIfBlank(payload.getErrorMsg(), toolResult)
                    );
                }
                return ToolExecutionOutcome.success(toolResult, llmObservation, payload.getStructuredOutput());
            } catch (Exception e) {
                // 仅对抛异常的瞬时失败做有界重试（网络/超时类）。
                lastError = e;
                if (attempt < attempts) {
                    log.warn("{} execute tool failed tool={} attempt={}/{} retrying errorType={} durationMs={}",
                            context.getRequestId(), toolName, attempt, attempts,
                            e.getClass().getSimpleName(),
                            (System.nanoTime() - attemptStartedAt) / 1_000_000L);
                    continue;
                }
                log.error("{} execute tool failed tool={} attempts={} errorType={} durationMs={}",
                        context.getRequestId(), toolName, attempts, e.getClass().getSimpleName(),
                        (System.nanoTime() - attemptStartedAt) / 1_000_000L);
            }
        }
        return ToolExecutionOutcome.failure(
                "Tool " + toolName + " Error.",
                "Tool " + toolName + " Error.",
                null,
                lastError == null ? "tool failed" : lastError.getMessage()
        );
    }

    /**
     * 并发执行多个工具调用。
     */
    public Map<String, String> executeTools(List<ToolCall> commands) {
        Map<String, ToolExecutionOutcome> outcomes = executeToolOutcomes(commands);
        Map<String, String> result = new LinkedHashMap<>(outcomes.size());
        for (Map.Entry<String, ToolExecutionOutcome> entry : outcomes.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().getLlmObservation());
        }
        return result;
    }

    /**
     * 并发执行多个工具调用，并返回完整 outcome。
     * 子类可以基于同一份 outcome 同时处理前端展示、记忆写回和账本一致性。
     */
    protected Map<String, ToolExecutionOutcome> executeToolOutcomes(List<ToolCall> commands) {
        Map<String, ToolExecutionOutcome> result = new ConcurrentHashMap<>();
        if (commands == null || commands.isEmpty()) {
            return result;
        }

        Map<String, Integer> dispatchIndexMapping = buildDispatchIndexMapping(commands);
        Map<String, Long> toolInvocationIds = ensureToolInvocationIds(commands);

        if (context != null && context.getAgentRunState() != null) {
            context.getAgentRunState().bindToolInvocationIds(toolInvocationIds);
        }
        emitToolCallRunningEvents(commands, dispatchIndexMapping);

        List<CompletableFuture<Void>> futures = new ArrayList<>(commands.size());
        Executor toolExecutor = resolveToolExecutor();
        for (ToolCall toolCall : commands) {
            CompletableFuture<Void> future = AgentExecutorSupport
                    .supplyAsync(toolExecutor, "toolBatch", () -> finalizeToolExecutionOutcome(toolCall, executeToolInternal(toolCall)))
                    .thenAccept(outcome -> {
                        result.put(toolCall.getId(), outcome);
                        finishToolInvocation(toolCall, outcome);
                        recordToolArtifacts(toolCall);
                        emitToolCallFinishedEvent(toolCall, dispatchIndexMapping.get(toolCall.getId()), outcome);
                    });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        Map<String, ToolExecutionOutcome> ordered = new LinkedHashMap<>(commands.size());
        for (ToolCall command : commands) {
            if (command != null && StringUtils.isNotBlank(command.getId())) {
                ordered.put(command.getId(), result.get(command.getId()));
            }
        }
        return ordered;
    }

    /**
     * 为同一批 tool call 固定 dispatchIndex，保证实时占位、终态更新与账本顺序一致。
     */
    private Map<String, Integer> buildDispatchIndexMapping(List<ToolCall> commands) {
        Map<String, Integer> dispatchIndexMapping = new LinkedHashMap<>();
        if (commands == null || commands.isEmpty()) {
            return dispatchIndexMapping;
        }
        int dispatchIndex = 1;
        for (ToolCall command : commands) {
            if (command == null || StringUtils.isBlank(command.getId())) {
                continue;
            }
            dispatchIndexMapping.put(command.getId(), dispatchIndex++);
        }
        return dispatchIndexMapping;
    }

    /**
     * 工具真正开始执行前先推送一条 tool_call 占位事件，
     * 让前端能够立刻展示“正在调用哪个工具”，避免长耗时工具阶段看起来像卡住。
     */
    private void emitToolCallRunningEvents(List<ToolCall> commands, Map<String, Integer> dispatchIndexMapping) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (ToolCall command : commands) {
            emitToolCallEvent(command, dispatchIndexMapping.get(command == null ? null : command.getId()), "running", false, null);
        }
    }

    /**
     * 工具完成后回写同一 messageId 的终态，前端可直接原位覆盖 running 卡片。
     */
    private void emitToolCallFinishedEvent(ToolCall command,
                                           Integer dispatchIndex,
                                           ToolExecutionOutcome outcome) {
        String status = outcome != null && outcome.isSuccess() ? "success" : "failed";
        emitToolCallEvent(command, dispatchIndex, status, true, outcome);
    }

    private void emitToolCallEvent(ToolCall command,
                                   Integer dispatchIndex,
                                   String status,
                                   boolean isFinal,
                                   ToolExecutionOutcome outcome) {
        if (printer == null || command == null || command.getFunction() == null) {
            return;
        }
        String toolCallId = command.getId();
        String toolName = command.getFunction().getName();
        if (StringUtils.isBlank(toolCallId) || StringUtils.isBlank(toolName)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageType", "tool_call");
        payload.put("status", status);
        payload.put("toolName", toolName);
        payload.put("toolCallId", toolCallId);
        payload.put("toolProvider", resolveToolProvider(toolName));
        if (dispatchIndex != null) {
            payload.put("dispatchIndex", dispatchIndex);
        }

        Long toolInvocationId = context == null || context.getAgentRunState() == null
                ? null
                : context.getAgentRunState().resolveToolInvocationId(toolCallId);
        if (toolInvocationId != null) {
            payload.put("toolInvocationId", String.valueOf(toolInvocationId));
        }

        Object input = parseToolCallInput(command.getFunction().getArguments());
        if (input != null) {
            payload.put("input", input);
        }

        payload.put("summary", buildToolCallSummary(toolName, status));
        payload.put("isFinal", isFinal);

        if (outcome != null && StringUtils.isNotBlank(outcome.getErrorMsg())) {
            payload.put("errorMsg", outcome.getErrorMsg());
        }

        printer.send(toolCallId, "tool_call", payload, isFinal);
    }

    private Object parseToolCallInput(String arguments) {
        String normalizedPayload = normalizeToolPayload(arguments);
        try {
            return new ObjectMapper().readValue(normalizedPayload, Object.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String buildToolCallSummary(String toolName, String status) {
        if ("success".equals(status)) {
            return toolName + " 调用完成";
        }
        if ("failed".equals(status)) {
            return toolName + " 调用失败";
        }
        return "正在调用 " + toolName;
    }

    /**
     * 主线程预登记工具调用，稳定保存 dispatchIndex 与 toolInvocationId。
     */
    protected Map<String, Long> ensureToolInvocationIds(List<ToolCall> commands) {
        if (context == null || context.getAgentRunState() == null || commands == null || commands.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> existing = new LinkedHashMap<>();
        List<ToolCall> missingCommands = new ArrayList<>();
        for (ToolCall command : commands) {
            if (command == null || StringUtils.isBlank(command.getId())) {
                continue;
            }
            Long existingInvocationId = context.getAgentRunState().resolveToolInvocationId(command.getId());
            if (existingInvocationId != null) {
                existing.put(command.getId(), existingInvocationId);
            } else {
                missingCommands.add(command);
            }
        }
        if (missingCommands.isEmpty()) {
            return existing;
        }
        Map<String, Long> created = preRegisterToolInvocations(missingCommands);
        if (existing.isEmpty()) {
            return created;
        }
        if (created.isEmpty()) {
            return existing;
        }
        existing.putAll(created);
        return existing;
    }

    protected Map<String, Long> preRegisterToolInvocations(List<ToolCall> commands) {
        if (context == null || !context.hasActiveLedgerRun() || context.getAgentRunState() == null) {
            return Map.of();
        }
        Long llmInvocationId = context.getAgentRunState().getCurrentLlmInvocationId();
        if (llmInvocationId == null) {
            return Map.of();
        }
        List<ToolInvocationBatchStartRecord.Item> items = new ArrayList<>(commands.size());
        int dispatchIndex = 1;
        for (ToolCall command : commands) {
            if (command == null || command.getFunction() == null || StringUtils.isBlank(command.getFunction().getName())) {
                continue;
            }
            items.add(ToolInvocationBatchStartRecord.Item.builder()
                    .toolCallId(command.getId())
                    .dispatchIndex(dispatchIndex++)
                    .toolName(command.getFunction().getName())
                    .toolProvider(resolveToolProvider(command.getFunction().getName()))
                    .inputJson(normalizeToolPayload(command.getFunction().getArguments()))
                    .startedAt(LocalDateTime.now())
                    .build());
        }
        if (items.isEmpty()) {
            return Map.of();
        }
        return context.getExecutionRecorder().createToolInvocations(ToolInvocationBatchStartRecord.builder()
                .runId(context.getAgentRunState().getRunId())
                .requestId(context.getRequestId())
                .llmInvocationId(llmInvocationId)
                .agentName(getName())
                .stepNo(getCurrentStep())
                .items(items)
                .build());
    }

    /**
     * 回写工具终态。
     */
    private void finishToolInvocation(ToolCall command, ToolExecutionOutcome outcome) {
        if (context == null || !context.hasActiveLedgerRun() || context.getAgentRunState() == null || command == null) {
            return;
        }
        Long toolInvocationId = context.getAgentRunState().resolveToolInvocationId(command.getId());
        if (toolInvocationId == null) {
            return;
        }
        context.getExecutionRecorder().finishToolInvocation(ToolInvocationFinishRecord.builder()
                .toolInvocationId(toolInvocationId)
                .runId(context.getAgentRunState().getRunId())
                .requestId(context.getRequestId())
                .sessionId(context.getSessionId())
                .toolCallId(command.getId())
                .toolName(command.getFunction().getName())
                .status(outcome != null && outcome.isSuccess()
                        ? ExecutionLedgerConstants.STATUS_SUCCESS
                        : ExecutionLedgerConstants.STATUS_FAILED)
                .llmObservation(outcome == null ? null : outcome.getLlmObservation())
                .structuredOutput(outcome == null ? null : outcome.getStructuredOutput())
                .errorMsg(outcome == null ? null : outcome.getErrorMsg())
                .finishedAt(LocalDateTime.now())
                .build());
    }

    /**
     * 收口当前 toolCall 生成的输出文件。
     */
    private void recordToolArtifacts(ToolCall command) {
        if (context == null || !context.hasActiveLedgerRun() || context.getAgentRunState() == null || command == null) {
            return;
        }
        Long toolInvocationId = context.getAgentRunState().resolveToolInvocationId(command.getId());
        if (toolInvocationId == null) {
            return;
        }
        List<ArtifactRecordCommand> artifactCommands = new ArrayList<>();
        for (var binding : context.getArtifactBindingsByToolCallId(command.getId())) {
            if (binding == null || binding.getSource() == null || binding.getFile() == null) {
                continue;
            }
            File file = binding.getFile();
            artifactCommands.add(ArtifactRecordCommand.builder()
                    .runId(context.getAgentRunState().getRunId())
                    .requestId(context.getRequestId())
                    .toolInvocationId(toolInvocationId)
                    .toolCallId(command.getId())
                    .artifactRole(ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT)
                    .visibility(binding.isInternalFile()
                            ? ExecutionLedgerConstants.VISIBILITY_INTERNAL
                            : ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                    .sourceType(ExecutionLedgerConstants.SOURCE_TYPE_TOOL_OUTPUT)
                    .sourceName(binding.getSource().getToolName())
                    .fileName(file.getFileName())
                    .storageKey(resolveStorageKey(file))
                    .downloadUrl(file.getOssUrl())
                    .previewUrl(file.getDomainUrl())
                    .fileSize(file.getFileSize() == null ? null : file.getFileSize().longValue())
                    .metadataJson(buildArtifactMetadata(file))
                    .build());
        }
        if (!artifactCommands.isEmpty()) {
            context.getExecutionRecorder().recordArtifacts(artifactCommands);
        }
    }

    private String normalizeToolPayload(String payload) {
        if (StringUtils.isBlank(payload)) {
            return "{}";
        }
        try {
            return new ObjectMapper().readTree(payload).toString();
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private ToolResultPayload normalizeToolResultPayload(Object rawResult, ObjectMapper mapper) {
        if (rawResult instanceof ToolResultPayload payload) {
            // rich tool 已经给出 typed output 时，只做 observation/failure 语义兜底，不再回退组装结构化 JSON。
            String toolResult = StringUtils.defaultString(payload.getToolResult());
            return ToolResultPayload.builder()
                    .toolResult(toolResult)
                    .llmObservation(StringUtils.defaultIfBlank(payload.getLlmObservation(), toolResult))
                    .structuredOutput(payload.getStructuredOutput())
                    .failed(Boolean.TRUE.equals(payload.getFailed()))
                    .errorMsg(payload.getErrorMsg())
                    .build();
        }
        if (rawResult instanceof String textResult) {
            return ToolResultPayload.builder()
                    .toolResult(textResult)
                    .llmObservation(textResult)
                    .failed(Boolean.FALSE)
                    .build();
        }
        try {
            String serialized = mapper.writeValueAsString(rawResult);
            return ToolResultPayload.builder()
                    .toolResult(serialized)
                    .llmObservation(serialized)
                    .failed(Boolean.FALSE)
                    .build();
        } catch (Exception e) {
            String fallback = String.valueOf(rawResult);
            return ToolResultPayload.builder()
                    .toolResult(fallback)
                    .llmObservation(fallback)
                    .failed(Boolean.FALSE)
                    .build();
        }
    }

    private String resolveToolProvider(String toolName) {
        if (availableTools == null || StringUtils.isBlank(toolName)) {
            return ExecutionLedgerConstants.TOOL_PROVIDER_LOCAL;
        }
        if (availableTools.getMcpToolMap() != null && availableTools.getMcpToolMap().containsKey(toolName)) {
            return ExecutionLedgerConstants.TOOL_PROVIDER_MCP;
        }
        return ExecutionLedgerConstants.TOOL_PROVIDER_LOCAL;
    }

    /**
     * 工具批量执行优先走运行时托管执行器；缺少上下文时回退当前线程，兼容单测夹具。
     */
    private Executor resolveToolExecutor() {
        if (context == null || context.getRuntimeDependencies() == null) {
            return Runnable::run;
        }
        return context.getRuntimeDependencies().requireToolExecutor();
    }

    private String resolveStorageKey(File file) {
        if (file == null) {
            return "";
        }
        if (StringUtils.isNotBlank(file.getOriginOssUrl())) {
            return file.getOriginOssUrl();
        }
        if (StringUtils.isNotBlank(file.getOssUrl())) {
            return file.getOssUrl();
        }
        if (StringUtils.isNotBlank(file.getOriginDomainUrl())) {
            return file.getOriginDomainUrl();
        }
        if (StringUtils.isNotBlank(file.getDomainUrl())) {
            return file.getDomainUrl();
        }
        return StringUtils.defaultString(file.getFileName());
    }

    private String buildArtifactMetadata(File file) {
        if (file == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(file.getDescription())) {
            metadata.put("description", file.getDescription());
        }
        if (StringUtils.isNotBlank(file.getOriginFileName())) {
            metadata.put("originFileName", file.getOriginFileName());
        }
        if (StringUtils.isNotBlank(file.getOriginDomainUrl())) {
            metadata.put("originDomainUrl", file.getOriginDomainUrl());
        }
        if (metadata.isEmpty()) {
            return null;
        }
        try {
            return new ObjectMapper().writeValueAsString(metadata);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 在工具实际执行完成后统一收口最终 observation。
     * 所有写库与写记忆都必须使用这一份 canonical 结果。
     */
    private ToolExecutionOutcome finalizeToolExecutionOutcome(ToolCall command, ToolExecutionOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        String toolCallId = command == null ? null : command.getId();
        return outcome.setLlmObservation(buildFinalLlmObservation(outcome.getLlmObservation(), toolCallId));
    }

    /**
     * 单次工具执行的内部结果。
     */
    @Data
    @Accessors(chain = true)
    protected static class ToolExecutionOutcome {
        private boolean success;
        private String toolResult;
        private String llmObservation;
        private ToolStructuredOutput structuredOutput;
        private String errorMsg;

        private static ToolExecutionOutcome success(String toolResult,
                                                    String llmObservation,
                                                    ToolStructuredOutput structuredOutput) {
            return new ToolExecutionOutcome()
                    .setSuccess(true)
                    .setToolResult(toolResult)
                    .setLlmObservation(llmObservation)
                    .setStructuredOutput(structuredOutput);
        }

        private static ToolExecutionOutcome failure(String toolResult,
                                                    String llmObservation,
                                                    ToolStructuredOutput structuredOutput,
                                                    String errorMsg) {
            return new ToolExecutionOutcome()
                    .setSuccess(false)
                    .setToolResult(toolResult)
                    .setLlmObservation(llmObservation)
                    .setStructuredOutput(structuredOutput)
                    .setErrorMsg(errorMsg);
        }
    }

}
