package com.linrun.agent.domain.agent.runtime.tool.common;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.work.TodoService;
import com.linrun.agent.domain.agent.runtime.work.TodoStepEvidenceScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model-facing adapter for the run-local TodoService. */
public class TodoWriteTool implements BaseTool {

    public static final String NAME = "todo_write";

    private final TodoService todoService;

    public TodoWriteTool() {
        this(new TodoService());
    }

    public TodoWriteTool(TodoService todoService) {
        this.todoService = todoService == null ? new TodoService() : todoService;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Maintain the current run's todo list. Use it for multi-step work. "
                + "On create/update declare evidence_policies aligned with steps: NONE for cognitive/control-plane work "
                + "that must not call a business tool, TOOL for work requiring a fresh successful tool call. "
                + "Keep exactly one actionable item in_progress, mark only verified work completed, "
                + "after each successful business tool reconcile its evidence by either completing the current item "
                + "or keeping it in_progress with partial evidence and notes, "
                + "and while reconciliation is pending use mark_step with at least one listed pending evidence ref, "
                + "preserve completed items when correcting the unfinished suffix, and finish only after every item is completed.";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of(
                "type", "string",
                "enum", List.of("create", "update", "mark_step", "finish"),
                "description", "Todo mutation to perform."
        ));
        properties.put("title", Map.of(
                "type", "string",
                "description", "Todo list title; required for create."
        ));
        properties.put("steps", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Complete ordered todo list for create, or replacement unfinished suffix for update."
        ));
        properties.put("evidence_policies", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "string",
                        "enum", List.of("NONE", "TOOL")
                ),
                "description", "One policy per step, aligned by index. Required for create/update in DEEP mode. NONE forbids business-tool evidence; TOOL requires fresh successful evidence from that step activation."
        ));
        properties.put("step_index", Map.of(
                "type", "integer",
                "description", "Zero-based item index for mark_step."
        ));
        properties.put("step_status", Map.of(
                "type", "string",
                "enum", List.of("not_started", "in_progress", "completed", "blocked"),
                "description", "New item status for mark_step."
        ));
        properties.put("step_notes", Map.of(
                "type", "string",
                "description", "Optional evidence or correction note for mark_step."
        ));
        properties.put("evidence_refs", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Fresh successful non-reused non-todo_write toolCallIds from the current step activation. Forbidden for NONE, required to complete TOOL, and never reusable across steps."
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("command")
        );
    }

    @Override
    public Object execute(Object input) {
        if (!(input instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("todo_write input must be an object");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        raw.forEach((key, value) -> params.put(String.valueOf(key), value));
        return todoService.execute(params);
    }

    public void setAgentContext(AgentContext agentContext) {
        todoService.setAgentContext(agentContext);
    }

    public TodoList getTodoListSnapshot() {
        return todoService.snapshot();
    }

    /** Current run-local Todo state for one ephemeral model system prompt. */
    public String getCurrentTodoStatePrompt() {
        return todoService.currentTodoStatePrompt();
    }

    /** Whether the next model turn must acknowledge successful business evidence first. */
    public boolean requiresEvidenceReconciliation() {
        return todoService.requiresEvidenceReconciliation();
    }

    /** Evidence identity captured by ToolDispatcher before a business call begins. */
    public TodoStepEvidenceScope getCurrentStepEvidenceScope() {
        return todoService.currentStepEvidenceScope();
    }

    /** Whether the current NONE item must expose only todo_write. */
    public boolean requiresTodoOnlyCurrentStep() {
        return todoService.requiresTodoOnlyCurrentStep();
    }
}
