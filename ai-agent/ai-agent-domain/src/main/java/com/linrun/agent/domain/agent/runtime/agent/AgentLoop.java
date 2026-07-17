package com.linrun.agent.domain.agent.runtime.agent;


import com.alibaba.fastjson.JSON;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.enums.RoleType;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.harness.AgentFutureWaiter;
import com.linrun.agent.domain.agent.runtime.harness.DefaultPermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.HookBus;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.StopGate;
import com.linrun.agent.domain.agent.runtime.llm.LLM;
import com.linrun.agent.domain.agent.runtime.loop.ContextPipeline;
import com.linrun.agent.domain.agent.runtime.loop.DefaultModelGateway;
import com.linrun.agent.domain.agent.runtime.loop.ModelGateway;
import com.linrun.agent.domain.agent.runtime.prompt.ToolCallPrompt;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.response.AgentResponse;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolExecutionOutcome;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;
import com.linrun.agent.domain.agent.runtime.completion.CompletionDecision;
import com.linrun.agent.domain.agent.runtime.completion.CompletionGate;
import com.linrun.agent.domain.agent.runtime.completion.CompletionOutputContractParser;
import com.linrun.agent.domain.agent.runtime.completion.CompletionRequest;
import com.linrun.agent.domain.agent.runtime.completion.DefaultCompletionGate;
import com.linrun.agent.domain.agent.runtime.completion.DeterministicFinalVerifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.LinkedHashSet;

/** One run-local model/tool harness. The model chooses from the active tool view. */
@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)
public class AgentLoop extends BaseAgent {

    private static final Set<String> REPORT_OUTPUT_STYLES = Set.of("html", "docs", "ppt");
    private static final CompletionOutputContractParser COMPLETION_OUTPUT_CONTRACT_PARSER =
            new CompletionOutputContractParser();

    private CompletionGate completionGate = new DefaultCompletionGate(new DeterministicFinalVerifier());
    private ContextPipeline contextPipeline = new ContextPipeline();
    private ModelGateway modelGateway;
    private ContextPipeline.PromptState promptState;

    /** Number of completion attempts made by this run-local loop. */
    private int completionAttempt;
    /** Stable semantic identity captured before volatile tool results are returned. */
    private transient String repetitionSignatureForTurn;


    // ===================== 核心状态字段 =====================
    /**
     * 大模型决策出的工具调用指令列表
     * 来源：think阶段调用LLM的askTool方法返回，包含待执行的工具名称、参数、调用ID等信息
     * 用途：act阶段根据该列表执行具体工具，是"思考"到"执行"的核心桥梁
     */
    private List<ToolCall> toolCalls;

    /**
     * 工具结果最大截断长度
     * 用途：避免工具返回超长结果（如大文本、大数据集）导致Token超限或处理异常，仅保留前N个字符
     * 取值：由外部配置/业务逻辑设置，null表示不截断
     */
    private Integer maxObserve;

    // ===================== 父类继承字段（关键说明） =====================
    // - name: 智能体名称（固定为"agent_loop"）
    // - description: 智能体描述（工具调用能力说明）
    // - systemPrompt: 系统提示词（指导大模型决策的核心指令，包含工具列表、任务规则等）
    // - nextStepPrompt: 下一步提示词（每次思考阶段向大模型发送的决策提示词）
    // - printer: 响应输出器（用于向客户端推送流式/非流式响应，如tool_thought、tool_result）
    // - maxSteps: 最大执行步数（防止智能体无限循环思考/执行）
    // - llm: 大模型实例（用于调用LLM生成工具调用指令）
    // - context: 智能体上下文（包含请求ID、用户查询、工具集合、文件信息、流式标识等核心上下文）
    // - availableTools: 可用工具集合（当前智能体可调用的所有工具）
    // - memory: 智能体记忆（存储对话历史、工具调用记录、执行结果等，保证上下文连续性）

    /**
     * 初始化统一 Agent Loop 的模型、提示词、工具目录和运行上限。
     * 核心逻辑：加载配置→构建提示词→初始化核心组件→设置初始状态
     *
     * @param context 智能体上下文（携带请求ID、用户查询、工具集合、文件信息等全量上下文）
     */
    public AgentLoop(AgentContext context) {
        this(context, new DefaultPermissionPolicy(), new HookBus());
    }

    /** Production constructor used by AgentLoopFactory to inject run-local Harness components. */
    public AgentLoop(AgentContext context, PermissionPolicy permissionPolicy, HookBus hookBus) {
        super(permissionPolicy, hookBus);
        // 步骤1：设置智能体基础标识
        setName(ExecutionLedgerConstants.AGENT_NAME_AGENT_LOOP);

        // 步骤2：加载显式注入的运行时配置
        ReactorRuntimeDependencies runtimeDependencies = requireRuntimeDependencies(context);
        ReactorConfig reactorConfig = runtimeDependencies.requireReactorConfig();
        setContext(context); // 提前绑定上下文，供基类公共提示词初始化逻辑复用

        availableTools = context.getToolCollection();
        promptState = contextPipeline.initialize(context, reactorConfig, availableTools);

        // 步骤5：初始化输出器和核心配置
        setPrinter(context.printer); // 响应输出器（推送tool_thought/tool_result给客户端）
        setRunBudget(new AgentRunBudget(
                positive(reactorConfig.getAgentLoopMaxTurns(), 40),
                positive(reactorConfig.getAgentLoopMaxToolCalls(), 64),
                positive(reactorConfig.getAgentLoopMaxCompletionAttempts(), 3),
                positive(reactorConfig.getAgentLoopMaxDurationSeconds(), 900L) * 1_000L,
                positive(reactorConfig.getAgentLoopMaxTotalTokens(), 200_000L),
                positive(reactorConfig.getAgentLoopMaxMicrocredits(), 10_000_000L)
        ));
        // 截断超长 observation，避免工具输出持续放大上下文。
        String maxObserveConfig = reactorConfig.getMaxObserve();
        if (maxObserveConfig != null && !maxObserveConfig.isBlank()) {
            try {
                setMaxObserve(Integer.parseInt(maxObserveConfig.trim()));
            } catch (NumberFormatException ignored) {
                // 配置非法则退回不截断的既有行为
            }
        }
        if (reactorConfig.getToolMaxAttempts() != null && reactorConfig.getToolMaxAttempts() > 0) {
            setToolMaxAttempts(reactorConfig.getToolMaxAttempts());
        }
        // 用户选择模型时优先按 modelId 覆盖，否则使用 Agent Loop 默认模型。
        LLM runtimeLlm = new LLM(
                runtimeDependencies.resolveEffectiveLlmSettings(
                        context.getModelIdOverride(), reactorConfig.getAgentLoopModelName()),
                "",
                runtimeDependencies);
        modelGateway = new DefaultModelGateway(runtimeLlm);
        setFunctionCallType(modelGateway.functionCallType());

        Set<String> availableToolNames = new LinkedHashSet<>(availableTools.getToolMap().keySet());
        availableToolNames.addAll(availableTools.getMcpToolMap().keySet());
        setSingleUseToolName(ExplicitToolChoicePolicy.resolveSingleUseRequiredToolName(
                context.getQuery(), availableToolNames));
    }

    /** Execute one model turn and, when requested, the selected tool calls. */
    @Override
    public String step() {
        repetitionSignatureForTurn = null;
        if (!runModelTurn()) {
            return "Thinking complete - no action needed";
        }
        return executeModelTurn();
    }

    @Override
    protected String repetitionSignature(String stepResult) {
        return repetitionSignatureForTurn == null
                ? super.repetitionSignature(stepResult)
                : repetitionSignatureForTurn;
    }

    /**
     * 执行一次模型决策轮次。
     * 核心逻辑：
     * 1. 动态替换提示词中的文件信息占位符；
     * 2. 补充用户消息（保证对话历史的合法性）；
     * 3. 调用大模型生成工具调用指令；
     * 4. 处理大模型响应，更新智能体记忆和工具调用列表；
     * 5. 异常处理：捕获异常并记录，标记智能体为完成状态。
     *
     * @return boolean 思考是否成功：true=成功生成工具调用指令，false=异常失败
     */
    private boolean runModelTurn() {
        int memorySizeBeforeTurn = getMemory().size();
        try {
            emitPhase("ANALYZING");
            context.setStreamMessageType("tool_thought");
            HookBus.HookDecision preModel = getHookBus().fire(new HookBus.HookEvent(
                    HookBus.HookPoint.PRE_MODEL, context, "model", getMemory().getMessages(), null));
            if (!preModel.allowed()) {
                throw new IllegalStateException(preModel.reason());
            }

            ReactorConfig reactorConfig = requireRuntimeDependencies(context).requireReactorConfig();
            ContextPipeline.PreparedModelTurn prepared = contextPipeline.prepareTurn(
                    context,
                    reactorConfig,
                    promptState,
                    getMemory(),
                    availableTools,
                    getCurrentStep());
            activateToolsForTurn(prepared.exposedTools());

            ModelGateway.ModelTurnResponse response = modelGateway.complete(
                    new ModelGateway.ModelTurnRequest(
                            context,
                            getMemory().getMessages(),
                            Message.systemMessage(prepared.systemPrompt(), null),
                            prepared.exposedTools(),
                            prepared.toolChoice(),
                            null,
                            Boolean.TRUE.equals(context.getIsStream()),
                            false,
                            300,
                             remainingRunDuration()
                     ));
            AgentStopReason postModelStop = getStopGate().afterModelCall(context, getRunBudget());
            if (postModelStop != AgentStopReason.NONE) {
                // ContextPipeline may append a synthetic user prompt immediately before
                // the provider call. A hard budget stop must not persist that prompt as
                // an assistant-visible conversation message or history replay artifact.
                discardSyntheticTurnMessages(memorySizeBeforeTurn);
                failModelTurn(postModelStop);
                return false;
            }
            if (!response.finishReason().permitsAgentContinuation()) {
                AgentStopReason finishStop = mapModelFinishReason(response.finishReason());
                log.warn("{} agent loop rejected non-normal model finish reason={} raw={}",
                        context.getRequestId(), response.finishReason(), response.rawFinishReason());
                discardSyntheticTurnMessages(memorySizeBeforeTurn);
                failModelTurn(finishStop);
                return false;
            }
            setToolCalls(response.toolCalls());
            getHookBus().fire(new HookBus.HookEvent(
                    HookBus.HookPoint.POST_MODEL, context, "model", null, response));

            if (!response.toolCalls().isEmpty()
                    && !Boolean.TRUE.equals(context.getIsStream())
                    && !response.content().isEmpty()) {
                printer.send("tool_thought", response.content());
            }

            Message assistantMsg;
            if (!response.toolCalls().isEmpty()
                    && !"struct_parse".equals(modelGateway.functionCallType())) {
                assistantMsg = Message.fromToolCalls(response.content(), response.toolCalls());
            } else {
                assistantMsg = Message.assistantMessage(response.content(), null);
            }
            getMemory().addMessage(assistantMsg);

        } catch (AgentFutureWaiter.RunDeadlineExceededException deadlineException) {
            log.warn("{} agent loop model turn stopped by run deadline",
                    context.getRequestId());
            context.markRunFailed();
            context.cancel(AgentStopReason.TIME_BUDGET);
            setStopReason(AgentStopReason.TIME_BUDGET);
            setState(AgentState.FINISHED);
            return false;
        } catch (AgentFutureWaiter.DownstreamAbortedException abortedException) {
            log.info("{} agent loop model turn cancelled after downstream disconnect",
                    context.getRequestId());
            context.markRunFailed();
            context.cancel(AgentStopReason.DOWNSTREAM_ABORTED);
            setStopReason(AgentStopReason.DOWNSTREAM_ABORTED);
            setState(AgentState.FINISHED);
            return false;
        } catch (AgentFutureWaiter.RunCancelledException cancelledException) {
            context.markRunFailed();
            setStopReason(cancelledException.getStopReason());
            setState(AgentState.FINISHED);
            return false;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            context.markRunFailed();
            AgentStopReason interruptedReason = context.isDownstreamAborted()
                    ? AgentStopReason.DOWNSTREAM_ABORTED
                    : AgentStopReason.EXECUTION_ERROR;
            context.cancel(interruptedReason);
            setStopReason(interruptedReason);
            setState(AgentState.FINISHED);
            return false;
        } catch (Exception e) {
            // 异常处理：记录错误日志，添加异常消息到记忆，标记智能体为完成状态
            log.error("{} agent loop model turn failed errorType={}",
                    context.getRequestId(), e.getClass().getSimpleName());
            getMemory().addMessage(Message.assistantMessage(
                    "Error encountered while processing: " + e.getMessage(), null));
            // 异常被吞掉后流程会降级走总结，这里标记 run 失败以保证账本终态反映真实结果；
            // LLM 配额已按每次调用独立结算。
            context.markRunFailed();
            context.cancel(AgentStopReason.MODEL_ERROR);
            setStopReason(AgentStopReason.MODEL_ERROR);
            setState(AgentState.FINISHED); // 标记智能体完成（终止后续流程）
            return false; // 思考失败
        }

        return true; // 思考成功
    }

    private void discardSyntheticTurnMessages(int memorySizeBeforeTurn) {
        if (memorySizeBeforeTurn < 0 || getMemory().size() <= memorySizeBeforeTurn) {
            return;
        }
        getMemory().getMessages().subList(memorySizeBeforeTurn, getMemory().size()).clear();
    }

    private void failModelTurn(AgentStopReason reason) {
        AgentStopReason effective = reason == null || reason == AgentStopReason.NONE
                ? AgentStopReason.MODEL_ERROR
                : reason;
        context.markRunFailed();
        context.cancel(effective);
        setStopReason(effective);
        setState(AgentState.FINISHED);
    }

    private AgentStopReason mapModelFinishReason(ModelGateway.ModelFinishReason finishReason) {
        if (finishReason == null) {
            return AgentStopReason.MODEL_STOP_REASON_UNSUPPORTED;
        }
        return switch (finishReason) {
            case MAX_TOKENS -> AgentStopReason.MODEL_MAX_TOKENS;
            case REFUSAL -> AgentStopReason.MODEL_REFUSAL;
            case CONTENT_FILTER -> AgentStopReason.MODEL_CONTENT_FILTER;
            case UNKNOWN -> AgentStopReason.MODEL_STOP_REASON_UNSUPPORTED;
            case NORMAL -> AgentStopReason.NONE;
        };
    }

    /**
     * 执行当前模型轮次选择的工具，或验证最终答案。
     * 核心逻辑：
     * 1. 校验工具调用列表：无工具则标记完成，返回最后一条消息内容；
     * 2. 执行工具：调用executeTools执行所有工具，获取执行结果；
     * 3. 处理工具结果：流式推送、截断超长结果、更新智能体记忆；
     * 4. 兼容两种工具调用模式：struct_parse（更新现有消息）、function_call（新增工具消息）；
     * 5. 聚合工具结果：返回所有工具结果的拼接字符串。
     *
     * @return String 所有工具执行结果的聚合字符串（换行分隔）
     */
    private String executeModelTurn() {
        // 步骤1：边界条件处理：无工具调用指令 → 标记智能体完成，返回最后一条消息内容
        if (toolCalls.isEmpty()) {
            String draftAnswer = getMemory().getLastMessage() == null
                    ? ""
                    : getMemory().getLastMessage().getContent();
            repetitionSignatureForTurn = StopGate.contentSignature(draftAnswer);
            completionAttempt++;
            emitPhase("VERIFYING");
            printer.send("verification_started", Map.of("attempt", completionAttempt));
            CompletionRequest completionRequest = buildCompletionRequest(draftAnswer);
            HookBus.HookDecision preCompletion = getHookBus().fire(new HookBus.HookEvent(
                    HookBus.HookPoint.PRE_COMPLETION,
                    context,
                    "completion",
                    completionRequest,
                    null));
            CompletionDecision decision = preCompletion.allowed()
                    ? completionGate.evaluate(completionRequest)
                    : CompletionDecision.builder()
                    .canStop(false)
                    .reasons(List.of(preCompletion.reason() == null
                            ? "Completion blocked by hook."
                            : preCompletion.reason()))
                    .requiredActions(List.of("Resolve the completion hook requirement and continue."))
                    .build();
            getHookBus().fire(new HookBus.HookEvent(
                    HookBus.HookPoint.POST_COMPLETION,
                    context,
                    "completion",
                    completionRequest,
                    decision));
            if (context != null && context.getAgentRunState() != null) {
                context.getAgentRunState().recordCompletionAttempt(
                        decision.isCanStop(), decision.isVerifierExecuted());
            }
            emitCompletionDecision(decision);
            if (decision.isCanStop()) {
                emitPhase("FINALIZING");
                setStopReason(AgentStopReason.COMPLETED);
                setState(AgentState.FINISHED);
                return draftAnswer;
            }
            String feedback = decision.toFeedbackMessage();
            Map<String, Object> blocked = new LinkedHashMap<>();
            blocked.put("attempt", completionAttempt);
            blocked.put("reasons", decision.getReasons());
            blocked.put("requiredActions", decision.getRequiredActions());
            printer.send("completion_blocked", blocked);
            getMemory().addMessage(Message.userMessage(feedback, null));
            if (completionAttempt >= getRunBudget().maxCompletionAttempts()) {
                context.markRunFailed();
                setStopReason(AgentStopReason.COMPLETION_ATTEMPT_BUDGET);
                setState(AgentState.FINISHED);
            }
            return feedback;
        }

        // 步骤2：执行工具调用（核心：调用executeTools方法执行所有工具，返回工具ID→结果的映射）
        repetitionSignatureForTurn = StopGate.toolCallsSignature(toolCalls);
        emitPhase("EXECUTING");
        Map<String, ToolExecutionOutcome> toolOutcomes = executeToolOutcomes(toolCalls);
        List<String> results = new ArrayList<>(); // 存储所有工具执行结果

        // 步骤3：遍历工具调用指令，处理每个工具的执行结果
        for (ToolCall command : toolCalls) {
            ToolExecutionOutcome outcome = toolOutcomes.get(command.getId());
            String toolResult = outcome == null ? "" : outcome.getToolResult();

            // 步骤3.1：特殊工具结果不推送（如代码解释器、报表工具等，避免前端展示冗余信息）
            if (!Arrays.asList("code_interpreter", "report_tool", "file_tool", "deep_search", "multimodalagent_tool", "data_analysis").contains(command.getFunction().getName())) {
                // 推送工具结果到客户端：包含工具名、参数、执行结果
                printer.send("tool_result", AgentResponse.ToolResult.builder()
                        .toolName(command.getFunction().getName())
                        .toolParam(parseToolParam(command))
                        .toolResult(toolResult)
                        .toolCallId(command.getId())
                        .build(), null);
            }

            // 步骤3.2：统一把最终 observation 写入主智能体记忆
            String result = writeToolObservationToMemory(command, outcome);

            // 步骤3.4：收集工具结果，用于最终聚合返回
            results.add(result);
        }

        // 步骤4：聚合所有工具结果（换行分隔），返回给上层流程
        return String.join("\n\n", results);
    }

    private CompletionRequest buildCompletionRequest(String draftAnswer) {
        String outputStyle = context == null ? null : context.getOutputStyle();
        boolean reportRequired = outputStyle != null
                && REPORT_OUTPUT_STYLES.contains(outputStyle.trim().toLowerCase());
        boolean reportPresent = context != null && context.getVisibleArtifactBindings().stream()
                .anyMatch(binding -> binding != null
                        && binding.getSource() != null
                        && "report_tool".equals(binding.getSource().getToolName()));
        return CompletionRequest.builder()
                .goal(context == null ? null : context.getQuery())
                .draftAnswer(draftAnswer)
                .executionProfile(context == null ? null : context.getExecutionProfile())
                .todoList(resolveTodoList())
                .toolEvidence(context == null ? List.of() : context.snapshotToolExecutionEvidence())
                .requiredToolName(resolveExplicitRequiredToolName())
                .toolInvocationContract(context == null
                        ? ToolInvocationContract.none()
                        : context.getToolInvocationContract())
                .requiredOutputFields(COMPLETION_OUTPUT_CONTRACT_PARSER
                        .parse(context == null ? null : context.getQuery())
                        .requiredFields())
                .runFailed(context != null && context.isRunFailed())
                .networkLookupRequired(context != null
                        && ExplicitToolChoicePolicy.requiresNetworkLookup(context.getQuery()))
                .reportArtifactRequired(reportRequired)
                .reportArtifactPresent(reportPresent)
                .build();
    }

    private String resolveExplicitRequiredToolName() {
        if (context == null || availableTools == null) {
            return null;
        }
        Set<String> availableToolNames = new LinkedHashSet<>();
        availableToolNames.addAll(availableTools.getToolMap().keySet());
        availableToolNames.addAll(availableTools.getMcpToolMap().keySet());
        return ExplicitToolChoicePolicy.resolveRequiredToolName(
                context.getQuery(), 1, availableToolNames);
    }

    private TodoList resolveTodoList() {
        if (availableTools == null) {
            return null;
        }
        BaseTool todo = availableTools.getTool(TodoWriteTool.NAME);
        if (todo instanceof TodoWriteTool todoWriteTool) {
            return todoWriteTool.getTodoListSnapshot();
        }
        return null;
    }

    private void emitCompletionDecision(CompletionDecision decision) {
        if (printer == null || decision == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accepted", decision.isCanStop());
        payload.put("canStop", decision.isCanStop());
        payload.put("status", decision.isCanStop() ? "passed" : "failed");
        payload.put("verdict", decision.isCanStop() ? "passed" : "failed");
        payload.put("attempt", completionAttempt);
        payload.put("failureReasons", decision.getReasons());
        payload.put("missingRequirements", decision.getReasons());
        payload.put("requiredActions", decision.getRequiredActions());
        payload.put("verifierExecuted", decision.isVerifierExecuted());
        printer.send("verification_result", payload);
    }

    private void emitPhase(String phase) {
        if (printer != null) {
            printer.send("phase_changed", Map.of("phase", phase));
        }
    }

    private Map<String, Object> parseToolParam(ToolCall command) {
        try {
            return JSON.parseObject(command.getFunction().getArguments(), Map.class);
        } catch (Exception e) {
            String rawArguments = command.getFunction().getArguments();
            log.warn("{} invalid tool arguments, fallback empty map tool={} argsChars={} errorType={}",
                    context.getRequestId(), command.getFunction().getName(),
                    rawArguments == null ? 0 : rawArguments.length(), e.getClass().getSimpleName());
            return Map.of();
        }
    }

    @Override
    protected Integer resolveMaxObserveLength() {
        return maxObserve;
    }

    private ReactorRuntimeDependencies requireRuntimeDependencies(AgentContext context) {
        if (context == null || context.getRuntimeDependencies() == null) {
            throw new IllegalStateException("AgentLoop 缺少 ReactorRuntimeDependencies");
        }
        return context.getRuntimeDependencies();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long positive(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
