package com.linrun.agent.domain.agent.runtime.loop;

import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.ExplicitToolChoicePolicy;
import com.linrun.agent.domain.agent.runtime.dto.Memory;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolChoice;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.enums.RoleType;
import com.linrun.agent.domain.agent.runtime.prompt.ToolCallPrompt;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;
import com.linrun.agent.domain.agent.runtime.tool.exposure.ToolExposurePolicy;
import com.linrun.agent.domain.agent.runtime.util.FileUtil;

import java.util.Map;

/**
 * Builds stable run prompts and prepares the exact context/tool view for one
 * model turn. Model transport and tool execution are deliberately outside it.
 */
public final class ContextPipeline {

    public PromptState initialize(AgentContext context,
                                  ReactorConfig config,
                                  ToolCollection toolCatalog) {
        String toolPrompt = buildToolPrompt(toolCatalog);
        String systemTemplate = promptTemplate(
                config == null ? null : config.getAgentLoopSystemPromptMap(),
                ToolCallPrompt.SYSTEM_PROMPT);
        String nextTemplate = promptTemplate(
                config == null ? null : config.getAgentLoopNextTurnPromptMap(),
                ToolCallPrompt.NEXT_STEP_PROMPT);

        String systemPrompt = injectHistoryDialogue(
                render(systemTemplate, context, toolPrompt),
                context == null ? null : context.getHistoryDialogue())
                + executionModeInstruction(context);
        String nextPrompt = render(nextTemplate, context, toolPrompt);
        return new PromptState(systemPrompt, nextPrompt);
    }

    public PreparedModelTurn prepareTurn(AgentContext context,
                                         ReactorConfig config,
                                         PromptState promptState,
                                         Memory memory,
                                         ToolCollection toolCatalog,
                                         int currentStep) {
        String files = FileUtil.formatFileInfo(
                context == null ? null : context.getProductFiles(), true);
        String systemPrompt = StringUtils.defaultString(promptState.systemPromptSnapshot())
                .replace("{{files}}", files);
        systemPrompt = appendCurrentTodoState(systemPrompt, toolCatalog);
        String nextPrompt = StringUtils.defaultString(promptState.nextStepPromptSnapshot())
                .replace("{{files}}", files);
        boolean todoCreationRequired = requiresDeepTodoCreation(context, toolCatalog);
        boolean evidenceReconciliationRequired = requiresEvidenceReconciliation(toolCatalog);
        boolean todoOnlyStepRequired = requiresTodoOnlyCurrentStep(toolCatalog);
        ensureNextUserMessage(memory, nextPrompt);

        ToolChoice toolChoice = resolveToolChoice(
                context,
                toolCatalog,
                currentStep,
                todoCreationRequired || evidenceReconciliationRequired || todoOnlyStepRequired);
        ToolCollection exposedTools = toolChoice == ToolChoice.NONE
                ? emptyToolView(toolCatalog)
                : todoCreationRequired || evidenceReconciliationRequired || todoOnlyStepRequired
                ? todoOnlyToolView(toolCatalog, context)
                : ToolExposurePolicy.selectForTurn(toolCatalog, context, config);
        return new PreparedModelTurn(systemPrompt, nextPrompt, exposedTools, toolChoice);
    }

    /**
     * Once a run has created an unfinished Todo, a no-tool model response would
     * only bounce against CompletionGate. Keep the loop action-oriented until
     * the authoritative Todo is complete; todo_write remains available for
     * advancing cognitive steps that do not need another business tool.
     */
    private ToolChoice resolveToolChoice(AgentContext context,
                                         ToolCollection toolCatalog,
                                         int currentStep,
                                         boolean todoStateTransitionRequired) {
        if (todoStateTransitionRequired) {
            return ToolChoice.REQUIRED;
        }
        ToolChoice resolved = ExplicitToolChoicePolicy.resolve(
                context == null ? null : context.getQuery(), currentStep);
        if (resolved == ToolChoice.NONE || !hasIncompleteTodo(toolCatalog)) {
            return resolved;
        }
        return ToolChoice.REQUIRED;
    }

    private boolean requiresDeepTodoCreation(AgentContext context,
                                             ToolCollection toolCatalog) {
        AgentExecutionProfile profile = context == null
                ? AgentExecutionProfile.STANDARD
                : context.getExecutionProfile();
        return profile == AgentExecutionProfile.DEEP && !hasTodo(toolCatalog);
    }

    private boolean hasTodo(ToolCollection toolCatalog) {
        TodoList todo = todoSnapshot(toolCatalog);
        return todo != null && todo.getSteps() != null && !todo.getSteps().isEmpty();
    }

    private boolean requiresEvidenceReconciliation(ToolCollection toolCatalog) {
        if (toolCatalog == null) {
            return false;
        }
        BaseTool todoTool = toolCatalog.getTool(TodoWriteTool.NAME);
        return todoTool instanceof TodoWriteTool todoWriteTool
                && todoWriteTool.requiresEvidenceReconciliation();
    }

    private boolean requiresTodoOnlyCurrentStep(ToolCollection toolCatalog) {
        if (toolCatalog == null) {
            return false;
        }
        BaseTool todoTool = toolCatalog.getTool(TodoWriteTool.NAME);
        return todoTool instanceof TodoWriteTool todoWriteTool
                && todoWriteTool.requiresTodoOnlyCurrentStep();
    }

    private boolean hasIncompleteTodo(ToolCollection toolCatalog) {
        TodoList todo = todoSnapshot(toolCatalog);
        if (todo == null || todo.getSteps() == null || todo.getSteps().isEmpty()) {
            return false;
        }
        if (todo.getStepStatus() == null || todo.getStepStatus().size() < todo.getSteps().size()) {
            return true;
        }
        return todo.getStepStatus().stream().anyMatch(status -> !"completed".equals(status));
    }

    private TodoList todoSnapshot(ToolCollection toolCatalog) {
        if (toolCatalog == null) {
            return null;
        }
        BaseTool todoTool = toolCatalog.getTool(TodoWriteTool.NAME);
        return todoTool instanceof TodoWriteTool todoWriteTool
                ? todoWriteTool.getTodoListSnapshot()
                : null;
    }

    /**
     * ToolChoice.NONE is enforced twice: no schema is sent to the provider and
     * the dispatcher receives an empty active view if a provider still emits a call.
     */
    private ToolCollection emptyToolView(ToolCollection toolCatalog) {
        return toolCatalog == null ? new ToolCollection() : toolCatalog.selectedView(java.util.Set.of());
    }

    /**
     * Deterministic Todo state gate: DEEP plan creation and evidence
     * reconciliation are control-plane transitions, so no business schema is
     * exposed in those turns. The dispatcher receives the same restricted view.
     */
    private ToolCollection todoOnlyToolView(ToolCollection toolCatalog,
                                            AgentContext context) {
        ToolCollection view = toolCatalog == null
                ? new ToolCollection()
                : toolCatalog.selectedView(java.util.Set.of(TodoWriteTool.NAME));
        if (context != null && context.getAgentRunState() != null && toolCatalog != null) {
            context.getAgentRunState().recordToolExposure(
                    toolCatalog.toolCount(),
                    view.toolCount(),
                    toolCatalog.getMcpToolMap().size(),
                    view.estimateSchemaChars());
        }
        return view;
    }

    private String appendCurrentTodoState(String systemPrompt, ToolCollection toolCatalog) {
        String todoState = "<current_todo_state>\nstate: none\n</current_todo_state>";
        if (toolCatalog != null) {
            BaseTool todoTool = toolCatalog.getTool(TodoWriteTool.NAME);
            if (todoTool instanceof TodoWriteTool todoWriteTool) {
                todoState = todoWriteTool.getCurrentTodoStatePrompt();
            }
        }
        String stablePrompt = StringUtils.defaultString(systemPrompt).stripTrailing();
        return stablePrompt.isEmpty() ? todoState : stablePrompt + "\n\n" + todoState;
    }

    private void ensureNextUserMessage(Memory memory, String nextPrompt) {
        if (memory == null) {
            return;
        }
        Message last = memory.getLastMessage();
        if (last == null || last.getRole() != RoleType.USER) {
            memory.addMessage(Message.userMessage(nextPrompt, null));
        }
    }

    private String promptTemplate(Map<String, String> promptMap, String fallback) {
        if (promptMap == null) {
            return fallback;
        }
        return promptMap.getOrDefault("default", fallback);
    }

    private String render(String template, AgentContext context, String toolPrompt) {
        return StringUtils.defaultString(template)
                .replace("{{tools}}", StringUtils.defaultString(toolPrompt))
                .replace("{{query}}", StringUtils.defaultString(context == null ? null : context.getQuery()))
                .replace("{{date}}", StringUtils.defaultString(context == null ? null : context.getDateInfo()))
                .replace("{{basePrompt}}", StringUtils.defaultString(context == null ? null : context.getBasePrompt()));
    }

    private String injectHistoryDialogue(String promptTemplate, String historyDialogue) {
        String template = StringUtils.defaultString(promptTemplate);
        String history = StringUtils.defaultString(historyDialogue);
        if (template.contains("{{history_dialogue}}")) {
            return template.replace("{{history_dialogue}}", history);
        }
        if (history.isBlank()) {
            return template;
        }
        return template + "\n\n## 用户历史对话信息\n<history_dialogue>\n"
                + history + "\n</history_dialogue>";
    }

    private String buildToolPrompt(ToolCollection tools) {
        if (tools == null || tools.getToolMap() == null || tools.getToolMap().isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder();
        for (BaseTool tool : tools.getToolMap().values()) {
            prompt.append(String.format("工具名：%s 工具描述：%s%n",
                    tool.getName(), tool.getDescription()));
        }
        return prompt.toString();
    }

    private String executionModeInstruction(AgentContext context) {
        AgentExecutionProfile profile = context == null
                ? AgentExecutionProfile.STANDARD
                : context.getExecutionProfile();
        if (profile == AgentExecutionProfile.DEEP) {
            return "\n# 执行模式\n- 当前为 DEEP：开始多步工作前必须创建 todo_write，并为每项声明 evidence_policy。NONE 认知步骤只用 todo_write 推进；TOOL 步骤必须使用该步骤激活期间产生的真实工具证据。";
        }
        if (profile == AgentExecutionProfile.AUTO) {
            return "\n# 执行模式\n- 当前为 AUTO：简单任务直接回答；多步、调研、代码修改或交付物任务先创建 todo_write，再逐项执行和验收。";
        }
        return "\n# 执行模式\n- 当前为 STANDARD：保持最小必要工具调用；若主动创建 todo_write，仍必须完整执行后才能结束。";
    }

    public record PromptState(String systemPromptSnapshot,
                              String nextStepPromptSnapshot) {
    }

    public record PreparedModelTurn(String systemPrompt,
                                    String nextStepPrompt,
                                    ToolCollection exposedTools,
                                    ToolChoice toolChoice) {
    }
}
