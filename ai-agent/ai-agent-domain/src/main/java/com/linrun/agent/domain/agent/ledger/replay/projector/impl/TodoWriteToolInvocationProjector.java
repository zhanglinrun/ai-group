package com.linrun.agent.domain.agent.ledger.replay.projector.impl;

import com.linrun.agent.domain.agent.ledger.model.ArtifactView;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationView;
import com.linrun.agent.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.TodoWriteToolOutput;
import com.linrun.agent.domain.agent.reactor.model.multi.EventResult;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;
import com.linrun.agent.domain.agent.runtime.tool.common.todo.TodoLifecycleService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replays native todo_write snapshots. */
public class TodoWriteToolInvocationProjector extends AbstractToolInvocationProjector {

    private static final String TODO_STATE_KEY = "__history_todo_list";
    private final TodoLifecycleService lifecycleService = new TodoLifecycleService();

    @Override
    public boolean supports(String toolName) {
        return "todo_write".equals(toolName);
    }

    @Override
    public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                              List<ArtifactView> artifacts,
                                              EventResult state) {
        TodoList todoList = resolveTodoList(invocation, state);
        if (todoList == null) {
            return List.of();
        }
        state.getResultMap().put(TODO_STATE_KEY, todoList.copy());
        return List.of(ProjectedReplayEvent.builder()
                .taskId(state.getTaskId())
                .taskOrder(state.getTaskOrder().getAndIncrement())
                .messageId(resolveMessageId(invocation, "todo_snapshot"))
                .messageType("agent_event")
                .messageOrder(state.getAndIncrOrder("todo_snapshot"))
                .resultMap(snapshotPayload(todoList))
                .build());
    }

    private TodoList resolveTodoList(ToolInvocationView invocation, EventResult state) {
        if (invocation != null && invocation.getStructuredOutput() instanceof TodoWriteToolOutput output) {
            return output.getAfterTodo() == null ? null : output.getAfterTodo().copy();
        }
        TodoList current = rememberedTodoList(state);
        Map<String, Object> input = readMap(invocation == null ? null : invocation.getInputJson());
        try {
            String command = String.valueOf(input.getOrDefault("command", ""));
            return switch (command) {
                case "create" -> createFromInput(input);
                case "update" -> current == null ? null : updateFromInput(current, input);
                case "mark_step" -> current == null ? null : applyMarkStep(current, input);
                case "finish" -> current == null ? null : lifecycleService.finish(current).getTodoList();
                default -> current;
            };
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return current;
        }
    }

    private Map<String, Object> snapshotPayload(TodoList todoList) {
        List<Map<String, Object>> todos = new ArrayList<>();
        List<String> steps = todoList.getSteps() == null ? List.of() : todoList.getSteps();
        List<String> statuses = todoList.getStepStatus() == null ? List.of() : todoList.getStepStatus();
        List<String> notes = todoList.getNotes() == null ? List.of() : todoList.getNotes();
        List<List<String>> evidenceRefs = todoList.getEvidenceRefs() == null ? List.of() : todoList.getEvidenceRefs();
        for (int index = 0; index < steps.size(); index++) {
            Map<String, Object> todo = new LinkedHashMap<>();
            todo.put("id", "todo-" + index);
            todo.put("title", steps.get(index));
            todo.put("status", index < statuses.size() ? statuses.get(index) : "not_started");
            todo.put("evidencePolicy", todoList.getEvidencePolicyAt(index).name());
            todo.put("activationId", todoList.getStepActivationIdAt(index));
            if (index < notes.size() && notes.get(index) != null && !notes.get(index).isBlank()) {
                todo.put("detail", notes.get(index));
            }
            todo.put("evidenceRefs", index < evidenceRefs.size() && evidenceRefs.get(index) != null
                    ? List.copyOf(evidenceRefs.get(index))
                    : List.of());
            todos.add(todo);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageType", "todo_snapshot");
        payload.put("title", todoList.getTitle());
        payload.put("todos", todos);
        return payload;
    }

    private TodoList rememberedTodoList(EventResult state) {
        if (state == null || state.getResultMap() == null) {
            return null;
        }
        Object value = state.getResultMap().get(TODO_STATE_KEY);
        return value instanceof TodoList todoList ? todoList.copy() : null;
    }

    private TodoList applyMarkStep(TodoList current, Map<String, Object> input) {
        Integer stepIndex = integerValue(input.get("step_index"));
        TodoList updated = lifecycleService.markStep(
                current,
                stepIndex,
                String.valueOf(input.get("step_status")),
                input.get("step_notes") == null ? null : String.valueOf(input.get("step_notes")))
                .getTodoList();
        if (input.containsKey("evidence_refs")) {
            updated.updateEvidenceRefs(stepIndex, stringList(input.get("evidence_refs")));
        }
        return updated;
    }

    private TodoList createFromInput(Map<String, Object> input) {
        List<String> steps = stringList(input.get("steps"));
        return lifecycleService.create(
                String.valueOf(input.getOrDefault("title", "")),
                steps,
                evidencePolicies(input.get("evidence_policies"), steps.size()))
                .getTodoList();
    }

    private TodoList updateFromInput(TodoList current, Map<String, Object> input) {
        List<String> steps = stringList(input.get("steps"));
        return lifecycleService.update(
                current,
                input.containsKey("title") ? String.valueOf(input.get("title")) : null,
                steps,
                evidencePolicies(input.get("evidence_policies"), steps.size()))
                .getTodoList();
    }

    private List<TodoEvidencePolicy> evidencePolicies(Object value, int size) {
        List<TodoEvidencePolicy> policies = new ArrayList<>(size);
        if (value instanceof List<?> list && list.size() == size) {
            for (Object item : list) {
                try {
                    TodoEvidencePolicy policy = TodoEvidencePolicy.valueOf(
                            String.valueOf(item).trim().toUpperCase());
                    policies.add(policy == TodoEvidencePolicy.LEGACY
                            ? TodoEvidencePolicy.LEGACY
                            : policy);
                } catch (IllegalArgumentException ignored) {
                    policies.add(TodoEvidencePolicy.LEGACY);
                }
            }
            return policies;
        }
        for (int index = 0; index < size; index++) {
            policies.add(TodoEvidencePolicy.LEGACY);
        }
        return policies;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
