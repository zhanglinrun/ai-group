package org.wwz.ai.domain.agent.runtime.agent;


import com.alibaba.fastjson.JSON;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolChoice;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;
import org.wwz.ai.domain.agent.runtime.llm.LLM;
import org.wwz.ai.domain.agent.runtime.prompt.ToolCallPrompt;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.util.FileUtil;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.response.AgentResponse;
import org.wwz.ai.domain.agent.runtime.ReactorRuntimeDependencies;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 工具调用代理 - 处理工具/函数调用的基础代理类
 */
@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)
public class ExecutorAgent extends ReActAgent {

    private static final String REQUIRED_TOOL_RETRY_PROMPT = """
            Your previous response described a tool action in plain text but did not emit a Function Call.
            Invoke one appropriate available tool now through the structured Function Calling protocol.
            Do not simulate a tool call or observation in text.
            """;
    private static final String AUTO_TOOL_RETRY_PROMPT = """
            Your previous response narrated a tool action without emitting a Function Call, so it produced no result.
            Complete the current task now. No explicit tool use was requested: prefer a concrete final answer for
            routine design, planning, or programming work. If external evidence is genuinely necessary, emit one
            structured Function Call. Do not simulate a tool call or observation in text.
            """;

    private List<ToolCall> toolCalls;
    private Integer maxObserve;
    private String systemPromptSnapshot;
    private String nextStepPromptSnapshot;
    private List<Message> lastRunEvaluationMessages = new ArrayList<>();
    private int evaluationMemoryStart;
    private boolean explicitToolRequirementSatisfied;
    private boolean currentRunExplicitToolRequirementSatisfied;

    private Integer taskId;

    public ExecutorAgent(AgentContext context) {
        setName("executor");
        setDescription("an agent that can execute tool calls.");
        ReactorRuntimeDependencies runtimeDependencies = requireRuntimeDependencies(context);
        ReactorConfig reactorConfig = runtimeDependencies.requireReactorConfig();

        StringBuilder toolPrompt = new StringBuilder();
        for (BaseTool tool : context.getToolCollection().getToolMap().values()) {
            toolPrompt.append(String.format("工具名：%s 工具描述：%s\n", tool.getName(), tool.getDescription()));
        }

        String promptKey = "default";
        String sopPromptKey = "default";
        String nextPromptKey = "default";
        setSystemPrompt(injectHistoryDialogue(
                reactorConfig.getExecutorSystemPromptMap().getOrDefault(promptKey, ToolCallPrompt.SYSTEM_PROMPT)
                        .replace("{{tools}}", toolPrompt.toString())
                        .replace("{{query}}", context.getQuery())
                        .replace("{{date}}", context.getDateInfo())
                        .replace("{{sopPrompt}}", context.getSopPrompt())
                        .replace("{{executorSopPrompt}}", reactorConfig.getExecutorSopPromptMap().getOrDefault(sopPromptKey, "")),
                context.getHistoryDialogue()));
        setNextStepPrompt(
                reactorConfig.getExecutorNextStepPromptMap().getOrDefault(nextPromptKey, ToolCallPrompt.NEXT_STEP_PROMPT)
                        .replace("{{tools}}", toolPrompt.toString())
                        .replace("{{query}}", context.getQuery())
                        .replace("{{date}}", context.getDateInfo())
                        .replace("{{sopPrompt}}", context.getSopPrompt())
                        .replace("{{executorSopPrompt}}", reactorConfig.getExecutorSopPromptMap().getOrDefault(sopPromptKey, "")));

        setSystemPromptSnapshot(getSystemPrompt());
        setNextStepPromptSnapshot(getNextStepPrompt());

        setPrinter(context.printer);
        // 修正：executor 应使用 executor.max_steps，此前误用 planner.max_steps 使 executor 步数上限配置失效（两者默认同为 40）。
        // 空值兜底：executor 未配置时回退 planner，再回退默认 40，避免裸配置构造下拆箱 NPE。
        Integer executorMaxSteps = reactorConfig.getExecutorMaxSteps();
        if (executorMaxSteps == null) {
            executorMaxSteps = reactorConfig.getPlannerMaxSteps();
        }
        setMaxSteps(executorMaxSteps != null ? executorMaxSteps : 40);
        // 用户选择模型时优先按 modelId 覆盖，否则回退 executor 配置模型
        setLlm(new LLM(runtimeDependencies.resolveEffectiveLlmSettings(context.getModelIdOverride(), reactorConfig.getExecutorModelName()), "", runtimeDependencies));

        setContext(context);
        setMaxObserve(Integer.parseInt(reactorConfig.getMaxObserve()));
        if (reactorConfig.getToolMaxAttempts() != null && reactorConfig.getToolMaxAttempts() > 0) {
            setToolMaxAttempts(reactorConfig.getToolMaxAttempts());
        }

        // 初始化工具集合
        availableTools = context.getToolCollection();
        setDigitalEmployeePrompt(reactorConfig.getDigitalEmployeePrompt());

        setTaskId(0);
    }

    @Override
    public boolean think() {
        // 获取文件内容
        String filesStr = FileUtil.formatFileInfo(context.getProductFiles(), true);
        setSystemPrompt(getSystemPromptSnapshot().replace("{{files}}", filesStr));
        setNextStepPrompt(getNextStepPromptSnapshot().replace("{{files}}", filesStr));

        if (!getMemory().getLastMessage().getRole().equals(RoleType.USER)) {
            Message userMsg = Message.userMessage(getNextStepPrompt(), null);
            getMemory().addMessage(userMsg);
        }

        try {
            // 获取带工具选项的响应
            log.info("{} executor ask tool {}", context.getRequestId(), JSON.toJSONString(availableTools));
            ToolChoice toolChoice = resolveExecutorToolChoice();
            CompletableFuture<LLM.ToolCallResponse> future = getLlm().askTool(
                    context,
                    getMemory().getMessages(),
                    Message.systemMessage(getSystemPrompt(), null),
                    availableTools,
                    toolChoice, null, false, 300
            );

            LLM.ToolCallResponse response = retryUnexecutedToolIntent(future.get());
            setToolCalls(response.getToolCalls() == null ? List.of() : response.getToolCalls());
            if (!toolCalls.isEmpty()) {
                explicitToolRequirementSatisfied = true;
                currentRunExplicitToolRequirementSatisfied = true;
            }

            // 记录响应信息
            if (response.getContent() != null && !response.getContent().trim().isEmpty()) {
                String thinkResult = response.getContent();
                String subType = "taskThought";
                if (toolCalls.isEmpty()) {
                    Map<String, Object> taskSummary = new HashMap<>();
                    taskSummary.put("taskSummary", response.getContent());
                    taskSummary.put("fileList", context.getTaskProductFiles());
                    thinkResult = JSON.toJSONString(taskSummary);
                    subType = "taskSummary";
                    printer.send("task_summary", taskSummary);
                } else {
                    printer.send("tool_thought", response.getContent());
                }

            }

            // 创建并添加助手消息
            Message assistantMsg = response.getToolCalls() != null && !response.getToolCalls().isEmpty() && !"struct_parse".equals(llm.getFunctionCallType()) ?
                    Message.fromToolCalls(response.getContent(), response.getToolCalls()) :
                    Message.assistantMessage(response.getContent(), null);
            getMemory().addMessage(assistantMsg);

        } catch (Exception e) {

            log.error("Oops! The " + getName() + "'s thinking process hit a snag: " + e.getMessage());
            getMemory().addMessage(Message.assistantMessage(
                    "Error encountered while processing: " + e.getMessage(), null));
            // 异常被吞掉后流程会降级继续，这里标记 run 失败，保证账本终态与配额结算反映真实结果
            context.markRunFailed();
            setState(AgentState.FINISHED);
            return false;
        }
        return true;
    }

    private LLM.ToolCallResponse retryUnexecutedToolIntent(LLM.ToolCallResponse response) throws Exception {
        if (!hasUnexecutedToolIntent(response) || toolUseIsProhibited()) {
            return response;
        }

        List<Message> retryMessages = new ArrayList<>(getMemory().getMessages());
        ToolChoice retryChoice = resolveTextualToolRetryChoice();
        retryMessages.add(Message.assistantMessage(response.getContent(), null));
        retryMessages.add(Message.userMessage(
                retryChoice == ToolChoice.REQUIRED ? REQUIRED_TOOL_RETRY_PROMPT : AUTO_TOOL_RETRY_PROMPT,
                null));
        log.info("{} executor detected an unexecuted textual tool action; retrying once with tool choice {}",
                context.getRequestId(), retryChoice);
        LLM.ToolCallResponse retry = getLlm().askTool(
                context,
                retryMessages,
                Message.systemMessage(getSystemPrompt(), null),
                availableTools,
                retryChoice,
                null,
                false,
                300
        ).get();
        if (retry == null) {
            return response;
        }
        boolean retryHasContent = StringUtils.isNotBlank(retry.getContent());
        boolean retryHasTools = retry.getToolCalls() != null && !retry.getToolCalls().isEmpty();
        return retryHasContent || retryHasTools ? retry : response;
    }

    private ToolChoice resolveTextualToolRetryChoice() {
        String originalQuery = context == null ? "" : StringUtils.defaultString(context.getQuery());
        String currentTask = context == null ? "" : StringUtils.defaultString(context.getTask());
        return ExplicitToolChoicePolicy.resolve(originalQuery + "\n" + currentTask, 1);
    }

    private boolean hasUnexecutedToolIntent(LLM.ToolCallResponse response) {
        if (response == null || response.getToolCalls() != null && !response.getToolCalls().isEmpty()
                || StringUtils.isBlank(response.getContent()) || availableTools == null
                || availableTools.getToolMap() == null || availableTools.getToolMap().isEmpty()) {
            return false;
        }
        String normalized = response.getContent().toLowerCase(Locale.ROOT);
        boolean hasAction = normalized.contains("行动：") || normalized.contains("行动:")
                || normalized.contains("action:");
        boolean finishes = normalized.contains("finish[") || normalized.contains("finish [");
        boolean bracketedAction = normalized.contains("[") && normalized.contains("]");
        return hasAction && bracketedAction && !finishes;
    }

    private boolean toolUseIsProhibited() {
        String originalQuery = context == null ? "" : StringUtils.defaultString(context.getQuery());
        String currentTask = context == null ? "" : StringUtils.defaultString(context.getTask());
        return ExplicitToolChoicePolicy.prohibitsToolUse(originalQuery + "\n" + currentTask);
    }

    @Override
    public String act() {
        if (toolCalls.isEmpty()) {
            ReactorConfig reactorConfig = requireRuntimeDependencies(context).requireReactorConfig();
            setState(AgentState.FINISHED);
            Message lastMessage = getMemory().getLastMessage();
            String executorResult = lastMessage == null ? "" : StringUtils.trimToEmpty(lastMessage.getContent());
            captureEvaluationMessages();
            // 删除工具结果
            if ("1".equals(reactorConfig.getClearToolMessage())) {
                getMemory().clearToolContext();
            }
            // 完成标记只用于提示 Planner 推进，不能覆盖 Executor 的真实步骤产物。
            String completionMarker = StringUtils.trimToEmpty(reactorConfig.getTaskCompleteDesc());
            if (StringUtils.isNotBlank(executorResult) && StringUtils.isNotBlank(completionMarker)) {
                return executorResult + "\n\n" + completionMarker;
            }
            return StringUtils.defaultIfBlank(executorResult, completionMarker);
        }

        Map<String, ToolExecutionOutcome> toolOutcomes = executeToolOutcomes(toolCalls);

        List<String> results = new ArrayList<>();
        for (ToolCall command : toolCalls) {
            ToolExecutionOutcome outcome = toolOutcomes.get(command.getId());
            String toolResult = outcome == null ? "" : outcome.getToolResult();
            if (!Arrays.asList("code_interpreter", "report_tool", "file_tool", "deep_search", "multimodalagent_tool", "data_analysis").contains(command.getFunction().getName())) {
                String toolName = command.getFunction().getName();
                printer.send("tool_result", AgentResponse.ToolResult.builder()
                                .toolName(toolName)
                                .toolParam(parseToolParam(command))
                                .toolResult(toolResult)
                                .toolCallId(command.getId())
                                .build(), null);
            }
            String result = writeToolObservationToMemory(command, outcome);
            results.add(result);
        }
        return String.join("\n\n", results);
    }

    private Map<String, Object> parseToolParam(ToolCall command) {
        try {
            return JSON.parseObject(command.getFunction().getArguments(), Map.class);
        } catch (Exception e) {
            log.warn("{} invalid tool arguments, fallback empty map. tool={}, args={}",
                    context.getRequestId(), command.getFunction().getName(), command.getFunction().getArguments());
            return Map.of();
        }
    }

    @Override
    public String run(String request) {
        generateDigitalEmployee(request);
        ReactorConfig reactorConfig = requireRuntimeDependencies(context).requireReactorConfig();
        request = reactorConfig.getTaskPrePrompt() + request;
        context.setTask(request);
        evaluationMemoryStart = getMemory().size();
        lastRunEvaluationMessages = new ArrayList<>();
        currentRunExplicitToolRequirementSatisfied = false;
        try {
            return super.run(request);
        } finally {
            if (lastRunEvaluationMessages.isEmpty()) {
                captureEvaluationMessages();
            }
        }
    }

    private void captureEvaluationMessages() {
        List<Message> messages = getMemory().getMessages();
        int start = Math.max(0, Math.min(evaluationMemoryStart, messages.size()));
        lastRunEvaluationMessages = new ArrayList<>(messages.subList(start, messages.size()));
    }

    protected ToolChoice resolveExecutorToolChoice() {
        if (currentRunExplicitToolRequirementSatisfied) {
            return ToolChoice.AUTO;
        }
        String originalQuery = context == null ? "" : StringUtils.defaultString(context.getQuery());
        String currentTask = context == null ? "" : StringUtils.defaultString(context.getTask());
        ToolChoice currentTaskChoice = ExplicitToolChoicePolicy.resolve(currentTask, 1);
        if (currentTaskChoice == ToolChoice.REQUIRED) {
            return ExplicitToolChoicePolicy.resolve(originalQuery + "\n" + currentTask, 1);
        }
        return explicitToolRequirementSatisfied
                ? ToolChoice.AUTO
                : ExplicitToolChoicePolicy.resolve(originalQuery + "\n" + currentTask, 1);
    }

    @Override
    protected Integer resolveMaxObserveLength() {
        return maxObserve;
    }

    private ReactorRuntimeDependencies requireRuntimeDependencies(AgentContext context) {
        if (context == null || context.getRuntimeDependencies() == null) {
            throw new IllegalStateException("ExecutorAgent 缺少 ReactorRuntimeDependencies");
        }
        return context.getRuntimeDependencies();
    }

}
