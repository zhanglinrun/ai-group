package com.linrun.agent.domain.agent.runtime.work;

import com.linrun.agent.domain.agent.ledger.model.tooloutput.TodoWriteToolOutput;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.completion.DefaultEvidenceValidator;
import com.linrun.agent.domain.agent.runtime.completion.ToolExecutionEvidence;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;
import com.linrun.agent.domain.agent.runtime.tool.common.todo.TodoLifecycleResult;
import com.linrun.agent.domain.agent.runtime.tool.common.todo.TodoLifecycleService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run-local owner of Todo state, lifecycle invariants, evidence and snapshots. */
public final class TodoService {

    private static final int MAX_EVIDENCE_REFS_IN_PROMPT = 3;
    private static final int MAX_AVAILABLE_EVIDENCE_IN_PROMPT = 5;
    private static final int MAX_PROMPT_VALUE_CHARS = 240;

    private final TodoLifecycleService lifecycleService;
    private final DefaultEvidenceValidator evidenceValidator;
    private final Set<String> acknowledgedBusinessEvidenceRefs = new LinkedHashSet<>();
    private AgentContext agentContext;
    private TodoList todoList;
    private TodoWriteToolOutput lastStructuredOutput;

    public TodoService() {
        this(new TodoLifecycleService(), new DefaultEvidenceValidator());
    }

    public TodoService(TodoLifecycleService lifecycleService,
                       DefaultEvidenceValidator evidenceValidator) {
        this.lifecycleService = lifecycleService == null ? new TodoLifecycleService() : lifecycleService;
        this.evidenceValidator = evidenceValidator == null
                ? new DefaultEvidenceValidator()
                : evidenceValidator;
    }

    public ToolResultPayload execute(Map<String, Object> params) {
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        String command = stringValue(safeParams.get("command"));
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        List<ToolExecutionEvidence> pendingEvidence = pendingSuccessfulBusinessEvidence();
        validateReconciliationMutation(command, safeParams, pendingEvidence);

        String observation = switch (command) {
            case "create" -> create(safeParams);
            case "update" -> update(safeParams);
            case "mark_step" -> markStep(safeParams);
            case "finish" -> finish();
            default -> throw new IllegalArgumentException("unknown todo_write command: " + command);
        };
        syncCurrentTask();
        emitSnapshot();
        acknowledgeReconciledEvidence(command, safeParams, pendingEvidence);
        return ToolResultPayload.structured(observation, observation, lastStructuredOutput);
    }

    private void validateReconciliationMutation(String command,
                                                Map<String, Object> params,
                                                List<ToolExecutionEvidence> pendingEvidence) {
        if (pendingEvidence == null || pendingEvidence.isEmpty()) {
            return;
        }
        if (!"mark_step".equals(command)) {
            throw new IllegalStateException(
                    "successful business evidence is pending reconciliation; call mark_step before " + command);
        }
        String status = stringValue(params.get("step_status"));
        if (!"completed".equals(status) && !"in_progress".equals(status)) {
            throw new IllegalArgumentException(
                    "pending business evidence can only be reconciled with mark_step completed or in_progress");
        }
        Set<String> pendingCallIds = pendingEvidence.stream()
                .map(ToolExecutionEvidence::getToolCallId)
                .filter(ref -> ref != null && !ref.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean consumesPendingEvidence = evidenceRefs(params.get("evidence_refs")).stream()
                .anyMatch(pendingCallIds::contains);
        if (!consumesPendingEvidence) {
            throw new IllegalArgumentException(
                    "evidence_refs must contain at least one successful toolCallId from pending business evidence");
        }
    }

    private void acknowledgeReconciledEvidence(String command,
                                               Map<String, Object> params,
                                               List<ToolExecutionEvidence> pendingEvidence) {
        if (!"mark_step".equals(command)) {
            return;
        }
        if (pendingEvidence == null) {
            pendingEvidence = List.of();
        }
        Set<String> pendingCallIds = pendingEvidence.stream()
                .map(ToolExecutionEvidence::getToolCallId)
                .filter(ref -> ref != null && !ref.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String evidenceRef : evidenceRefs(params.get("evidence_refs"))) {
            if (pendingCallIds.isEmpty() || pendingCallIds.contains(evidenceRef)) {
                acknowledgedBusinessEvidenceRefs.add(evidenceRef);
            }
        }
    }

    private String create(Map<String, Object> params) {
        if (todoList != null) {
            throw new IllegalStateException("a todo list already exists; update the unfinished suffix instead");
        }
        String title = stringValue(params.get("title"));
        List<String> steps = stringList(params.get("steps"));
        if (title == null || title.isBlank() || steps.isEmpty()) {
            throw new IllegalArgumentException("title and non-empty steps are required for create");
        }
        List<TodoEvidencePolicy> evidencePolicies = evidencePolicies(
                params.get("evidence_policies"), steps, "create");
        TodoList before = snapshot();
        TodoLifecycleResult result = lifecycleService.create(title, steps, evidencePolicies);
        todoList = result.getTodoList();
        lastStructuredOutput = output("create", before, result);
        return "Todo list created; execute the current in-progress item.";
    }

    private String update(Map<String, Object> params) {
        requireTodoList();
        List<String> remaining = stringList(params.get("steps"));
        if (remaining.isEmpty()) {
            throw new IllegalArgumentException("non-empty steps are required for update");
        }
        List<TodoEvidencePolicy> evidencePolicies = evidencePolicies(
                params.get("evidence_policies"), remaining, "update");
        TodoList before = snapshot();
        TodoLifecycleResult result = lifecycleService.update(
                todoList, stringValue(params.get("title")), remaining, evidencePolicies);
        todoList = result.getTodoList();
        lastStructuredOutput = output("update", before, result);
        return "Unfinished todos updated; completed items were preserved.";
    }

    private String markStep(Map<String, Object> params) {
        requireTodoList();
        Integer index = integerValue(params.get("step_index"));
        String status = stringValue(params.get("step_status"));
        List<String> evidenceRefs = evidenceRefs(params.get("evidence_refs"));
        validateEvidenceRefs(index, status, evidenceRefs);
        TodoList before = snapshot();
        TodoLifecycleResult result = lifecycleService.markStep(
                todoList, index, status, stringValue(params.get("step_notes")));
        todoList = result.getTodoList();
        TodoEvidencePolicy evidencePolicy = todoList.getEvidencePolicyAt(index);
        if (evidencePolicy == TodoEvidencePolicy.LEGACY) {
            if (params.containsKey("evidence_refs") || "completed".equals(status)) {
                todoList.updateEvidenceRefs(index, evidenceRefs);
            }
        } else if (evidencePolicy == TodoEvidencePolicy.NONE) {
            todoList.updateEvidenceRefs(index, List.of());
        } else if (!evidenceRefs.isEmpty()) {
            todoList.updateEvidenceRefs(index, mergeEvidenceRefs(existingEvidenceRefs(index), evidenceRefs));
        }
        lastStructuredOutput = output("mark_step", before, result);
        return Boolean.TRUE.equals(result.getAutoFinished())
                ? "All todos are completed."
                : "Todo status updated; continue with the current in-progress item.";
    }

    private String finish() {
        requireTodoList();
        TodoList before = snapshot();
        TodoLifecycleResult result = lifecycleService.finish(todoList);
        todoList = result.getTodoList();
        lastStructuredOutput = output("finish", before, result);
        return "Todo list verified complete.";
    }

    private void validateEvidenceRefs(Integer stepIndex,
                                      String status,
                                      List<String> evidenceRefs) {
        if (stepIndex == null || todoList == null || todoList.getSteps() == null
                || stepIndex < 0 || stepIndex >= todoList.getSteps().size()) {
            return;
        }
        TodoEvidencePolicy evidencePolicy = todoList.getEvidencePolicyAt(stepIndex);
        if (evidencePolicy == TodoEvidencePolicy.NONE) {
            if (evidenceRefs != null && !evidenceRefs.isEmpty()) {
                throw new IllegalArgumentException(
                        "evidence_policy NONE forbids business evidence_refs for this todo item");
            }
            return;
        }
        if (evidencePolicy == TodoEvidencePolicy.TOOL) {
            validateScopedToolEvidence(stepIndex, status, evidenceRefs);
            return;
        }
        validateLegacyEvidenceRefs(status, evidenceRefs);
    }

    private void validateScopedToolEvidence(int stepIndex,
                                            String status,
                                            List<String> evidenceRefs) {
        if (evidenceRefs != null && !evidenceRefs.isEmpty()
                && !"completed".equals(status) && !"in_progress".equals(status)) {
            throw new IllegalArgumentException(
                    "evidence_refs are only allowed for completed or in_progress todo items");
        }
        List<String> existingRefs = existingEvidenceRefs(stepIndex);
        List<String> duplicateRefs = evidenceRefs == null
                ? List.of()
                : evidenceRefs.stream().filter(allAssignedEvidenceRefs()::contains).toList();
        if (!duplicateRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "evidence_refs cannot be consumed more than once: " + duplicateRefs);
        }
        List<String> combinedRefs = mergeEvidenceRefs(existingRefs, evidenceRefs);
        if ("completed".equals(status) && combinedRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "evidence_policy TOOL requires at least one successful toolCallId from the current todo activation");
        }
        if (combinedRefs.isEmpty()) {
            return;
        }
        if (agentContext == null) {
            throw new IllegalStateException("tool evidence cannot be verified without an AgentContext");
        }
        Long activationId = todoList.getStepActivationIdAt(stepIndex);
        List<ToolExecutionEvidence> evidence = agentContext.snapshotToolExecutionEvidence();
        List<String> invalidRefs = combinedRefs.stream()
                .filter(ref -> !evidenceValidator.isSuccessfulBusinessEvidenceForStep(
                        ref, evidence, stepIndex, activationId))
                .toList();
        if (!invalidRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "evidence_refs must reference successful, non-reused tool calls from the current todo activation; invalid refs: "
                            + invalidRefs);
        }
    }

    private void validateLegacyEvidenceRefs(String status, List<String> evidenceRefs) {
        if (agentContext == null) {
            return;
        }
        List<ToolExecutionEvidence> evidence = agentContext.snapshotToolExecutionEvidence();
        List<ToolExecutionEvidence> nonTodoEvidence = evidence.stream()
                .filter(item -> item != null
                        && item.getToolName() != null
                        && !item.getToolName().isBlank()
                        && !TodoWriteTool.NAME.equals(item.getToolName()))
                .toList();
        boolean refsRequired = agentContext.getExecutionProfile() == AgentExecutionProfile.DEEP
                || !nonTodoEvidence.isEmpty();
        if ("completed".equals(status)
                && (evidenceRefs == null || evidenceRefs.isEmpty())
                && refsRequired) {
            throw new IllegalArgumentException(
                    "evidence_refs must contain at least one successful toolCallId in DEEP mode or after non-todo tools have run");
        }
        if (evidenceRefs == null || evidenceRefs.isEmpty()) {
            return;
        }
        List<String> invalidRefs = evidenceRefs.stream()
                .filter(ref -> !evidenceValidator.isSuccessfulBusinessEvidence(ref, evidence))
                .toList();
        if (!invalidRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "evidence_refs must reference successful tool calls; invalid refs: " + invalidRefs);
        }
    }

    private void emitSnapshot() {
        if (agentContext == null || agentContext.getPrinter() == null || todoList == null) {
            return;
        }
        agentContext.getPrinter().send("phase_changed", Map.of("phase", "PLANNING"));
        agentContext.getPrinter().send("todo_snapshot", toTodoSnapshot(todoList.copy()));
    }

    private Map<String, Object> toTodoSnapshot(TodoList snapshot) {
        List<Map<String, Object>> todos = new ArrayList<>();
        List<String> steps = snapshot.getSteps() == null ? List.of() : snapshot.getSteps();
        List<String> statuses = snapshot.getStepStatus() == null ? List.of() : snapshot.getStepStatus();
        List<String> notes = snapshot.getNotes() == null ? List.of() : snapshot.getNotes();
        List<List<String>> refs = snapshot.getEvidenceRefs() == null ? List.of() : snapshot.getEvidenceRefs();
        for (int index = 0; index < steps.size(); index++) {
            Map<String, Object> todo = new LinkedHashMap<>();
            todo.put("id", "todo-" + index);
            todo.put("title", steps.get(index));
            todo.put("status", index < statuses.size() ? statuses.get(index) : "not_started");
            todo.put("evidencePolicy", snapshot.getEvidencePolicyAt(index).name());
            todo.put("activationId", snapshot.getStepActivationIdAt(index));
            if (index < notes.size() && notes.get(index) != null && !notes.get(index).isBlank()) {
                todo.put("detail", notes.get(index));
            }
            todo.put("evidenceRefs", index < refs.size() && refs.get(index) != null
                    ? List.copyOf(refs.get(index))
                    : List.of());
            todos.add(todo);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", snapshot.getTitle());
        payload.put("todos", todos);
        return payload;
    }

    private TodoWriteToolOutput output(String command,
                                       TodoList before,
                                       TodoLifecycleResult result) {
        return TodoWriteToolOutput.builder()
                .command(command)
                .beforeTodo(before)
                .afterTodo(result == null || result.getTodoList() == null
                        ? null : result.getTodoList().copy())
                .currentStep(result == null ? null : result.getCurrentStep())
                .currentStepIndex(result == null ? null : result.getCurrentStepIndex())
                .autoAdvanced(result != null && Boolean.TRUE.equals(result.getAutoAdvanced()))
                .autoFinished(result != null && Boolean.TRUE.equals(result.getAutoFinished()))
                .build();
    }

    private void requireTodoList() {
        if (todoList == null) {
            throw new IllegalStateException("no todo list exists; create one first");
        }
    }

    public void setAgentContext(AgentContext agentContext) {
        this.agentContext = agentContext;
        if (todoList != null) {
            syncCurrentTask();
        }
    }

    public TodoList snapshot() {
        return todoList == null ? null : todoList.copy();
    }

    /** Return the current item's immutable tool-evidence scope, if one is active. */
    public TodoStepEvidenceScope currentStepEvidenceScope() {
        if (todoList == null) {
            return null;
        }
        Integer stepIndex = todoList.getCurrentStepIndex();
        if (stepIndex == null) {
            return null;
        }
        Long activationId = todoList.getStepActivationIdAt(stepIndex);
        if (activationId == null) {
            return null;
        }
        return new TodoStepEvidenceScope(
                stepIndex,
                activationId,
                todoList.getEvidencePolicyAt(stepIndex));
    }

    /** NONE items are control-plane work: only todo_write may be exposed. */
    public boolean requiresTodoOnlyCurrentStep() {
        TodoStepEvidenceScope scope = currentStepEvidenceScope();
        return scope != null && scope.evidencePolicy() == TodoEvidencePolicy.NONE;
    }

    /**
     * Build the authoritative run-local Todo snapshot for one model request.
     * The caller appends this block to the ephemeral system prompt; it is never
     * written into conversation Memory.
     */
    public String currentTodoStatePrompt() {
        TodoList current = snapshot();
        StringBuilder prompt = new StringBuilder("<current_todo_state>\n");
        if (current == null) {
            return prompt.append("state: none\n</current_todo_state>").toString();
        }

        List<String> steps = current.getSteps() == null ? List.of() : current.getSteps();
        List<String> statuses = current.getStepStatus() == null ? List.of() : current.getStepStatus();
        prompt.append("title: ").append(compactPromptValue(current.getTitle())).append('\n');

        int completedPrefixSize = 0;
        while (completedPrefixSize < steps.size()
                && "completed".equals(valueAt(statuses, completedPrefixSize, "not_started"))) {
            completedPrefixSize++;
        }
        appendRangeGroup(prompt, "completed_prefix", current, 0, completedPrefixSize);

        Integer currentStepIndex = current.getCurrentStepIndex();
        appendSingleGroup(prompt, "in_progress", current, currentStepIndex);

        prompt.append("pending_suffix:\n");
        boolean appendedPending = false;
        for (int index = completedPrefixSize; index < steps.size(); index++) {
            if (currentStepIndex != null && currentStepIndex == index) {
                continue;
            }
            appendItem(prompt, current, index);
            appendedPending = true;
        }
        if (!appendedPending) {
            prompt.append("- none\n");
        }
        appendPendingSuccessfulEvidence(prompt);
        appendAvailableSuccessfulEvidence(prompt);
        boolean reconciliationRequired = requiresEvidenceReconciliation();
        prompt.append("evidence_reconciliation_required: ")
                .append(reconciliationRequired)
                .append('\n');
        if (reconciliationRequired) {
            prompt.append("reconciliation_instruction: Call todo_write before any further business tool. ")
                    .append("If the listed evidence fully proves the in_progress item, mark it completed with evidence_refs; ")
                    .append("otherwise keep it in_progress and record the partial evidence and notes. ")
                    .append("The evidence_refs must include at least one id from pending_successful_evidence; ")
                    .append("create, update, finish, empty refs, and already acknowledged refs cannot clear this gate. ")
                    .append("Do not repeat an identical successful business operation only to obtain a new toolCallId.\n");
        }
        TodoStepEvidenceScope currentScope = currentStepEvidenceScope();
        if (currentScope != null && currentScope.evidencePolicy() == TodoEvidencePolicy.NONE) {
            prompt.append("current_step_instruction: This item declares evidence_policy=NONE. ")
                    .append("Do not call a business tool and do not attach evidence_refs; use todo_write to complete the cognitive/control-plane work.\n");
        } else if (currentScope != null && currentScope.evidencePolicy() == TodoEvidencePolicy.TOOL) {
            prompt.append("current_step_instruction: This item declares evidence_policy=TOOL. ")
                    .append("Use the needed business tool once, then attach its successful non-reused toolCallId to this activation only.\n");
        }
        if (current.getStepStatus() != null
                && current.getStepStatus().stream().anyMatch(status -> !"completed".equals(status))) {
            prompt.append("instruction: Do not produce a final answer while this Todo is incomplete. ")
                    .append("Continue only the in_progress item and follow its evidence_policy, then call todo_write ")
                    .append("to record progress and advance. Never recreate completed work.\n");
        }
        return prompt.append("</current_todo_state>").toString();
    }

    /**
     * A successful business result must be acknowledged by a successful Todo
     * mutation before another business operation is exposed. Failed Todo writes
     * deliberately do not clear the gate, while a successful in_progress update
     * may record partial evidence without pretending the item is complete.
     */
    public boolean requiresEvidenceReconciliation() {
        return !pendingSuccessfulBusinessEvidence().isEmpty();
    }

    private boolean hasIncompleteTodo() {
        if (todoList == null || todoList.getSteps() == null || todoList.getSteps().isEmpty()) {
            return false;
        }
        return todoList.getStepStatus() == null
                || todoList.getStepStatus().size() < todoList.getSteps().size()
                || todoList.getStepStatus().stream().anyMatch(status -> !"completed".equals(status));
    }

    private void appendAvailableSuccessfulEvidence(StringBuilder prompt) {
        prompt.append("available_successful_evidence:\n");
        if (agentContext == null) {
            prompt.append("- none\n");
            return;
        }
        List<ToolExecutionEvidence> evidence = agentContext.snapshotToolExecutionEvidence();
        TodoStepEvidenceScope currentScope = currentStepEvidenceScope();
        Set<String> includedCallIds = new LinkedHashSet<>();
        Set<String> assignedRefs = allAssignedEvidenceRefs();
        int included = 0;
        for (int index = evidence.size() - 1;
             index >= 0 && included < MAX_AVAILABLE_EVIDENCE_IN_PROMPT;
             index--) {
            ToolExecutionEvidence item = evidence.get(index);
            if (item == null || !item.isSuccess()
                    || item.isReused()
                    || TodoWriteTool.NAME.equals(item.getToolName())
                    || item.getToolName() == null || item.getToolName().isBlank()
                    || item.getToolCallId() == null || item.getToolCallId().isBlank()
                    || assignedRefs.contains(item.getToolCallId())
                    || !isEvidenceVisibleForCurrentStep(item, currentScope)
                    || !includedCallIds.add(item.getToolCallId())) {
                continue;
            }
            prompt.append("- tool_call_id=")
                    .append(compactPromptValue(item.getToolCallId()))
                    .append(" | tool=")
                    .append(compactPromptValue(item.getToolName()))
                    .append('\n');
            included++;
        }
        if (included == 0) {
            prompt.append("- none\n");
        }
    }

    private void appendPendingSuccessfulEvidence(StringBuilder prompt) {
        prompt.append("pending_successful_evidence:\n");
        List<ToolExecutionEvidence> evidence = pendingSuccessfulBusinessEvidence();
        Set<String> includedCallIds = new LinkedHashSet<>();
        int included = 0;
        for (int index = evidence.size() - 1;
             index >= 0 && included < MAX_AVAILABLE_EVIDENCE_IN_PROMPT;
             index--) {
            ToolExecutionEvidence item = evidence.get(index);
            if (item == null || !item.isSuccess()
                    || TodoWriteTool.NAME.equals(item.getToolName())
                    || item.getToolName() == null || item.getToolName().isBlank()
                    || item.getToolCallId() == null || item.getToolCallId().isBlank()
                    || !includedCallIds.add(item.getToolCallId())) {
                continue;
            }
            prompt.append("- tool_call_id=")
                    .append(compactPromptValue(item.getToolCallId()))
                    .append(" | tool=")
                    .append(compactPromptValue(item.getToolName()))
                    .append('\n');
            included++;
        }
        if (included == 0) {
            prompt.append("- none\n");
        }
    }

    private List<ToolExecutionEvidence> pendingSuccessfulBusinessEvidence() {
        if (!hasIncompleteTodo() || agentContext == null) {
            return List.of();
        }
        TodoStepEvidenceScope currentScope = currentStepEvidenceScope();
        if (currentScope != null && currentScope.evidencePolicy() == TodoEvidencePolicy.NONE) {
            return List.of();
        }
        Set<String> assignedRefs = allAssignedEvidenceRefs();
        return agentContext.snapshotToolExecutionEvidence().stream()
                .filter(item -> item != null
                        && item.isSuccess()
                        && !item.isReused()
                        && item.getToolName() != null
                        && !item.getToolName().isBlank()
                        && !TodoWriteTool.NAME.equals(item.getToolName())
                        && item.getToolCallId() != null
                        && !item.getToolCallId().isBlank()
                        && !assignedRefs.contains(item.getToolCallId())
                        && isEvidenceVisibleForCurrentStep(item, currentScope)
                        && !acknowledgedBusinessEvidenceRefs.contains(item.getToolCallId()))
                .toList();
    }

    private boolean isEvidenceVisibleForCurrentStep(ToolExecutionEvidence evidence,
                                                    TodoStepEvidenceScope currentScope) {
        if (currentScope == null || currentScope.evidencePolicy() == TodoEvidencePolicy.LEGACY) {
            return true;
        }
        return currentScope.evidencePolicy() == TodoEvidencePolicy.TOOL
                && evidence != null
                && evidence.getTodoStepIndex() != null
                && evidence.getTodoStepActivationId() != null
                && evidence.getTodoStepIndex() == currentScope.stepIndex()
                && evidence.getTodoStepActivationId() == currentScope.activationId();
    }

    private void syncCurrentTask() {
        if (agentContext == null) {
            return;
        }
        String currentStep = todoList == null ? "" : todoList.getCurrentStep();
        agentContext.setTask(currentStep == null ? "" : currentStep);
    }

    private void appendRangeGroup(StringBuilder prompt,
                                  String groupName,
                                  TodoList current,
                                  int fromInclusive,
                                  int toExclusive) {
        prompt.append(groupName).append(":\n");
        if (fromInclusive >= toExclusive) {
            prompt.append("- none\n");
            return;
        }
        for (int index = fromInclusive; index < toExclusive; index++) {
            appendItem(prompt, current, index);
        }
    }

    private void appendSingleGroup(StringBuilder prompt,
                                   String groupName,
                                   TodoList current,
                                   Integer index) {
        prompt.append(groupName).append(":\n");
        if (index == null) {
            prompt.append("- none\n");
            return;
        }
        appendItem(prompt, current, index);
    }

    private void appendItem(StringBuilder prompt, TodoList current, int index) {
        List<String> steps = current.getSteps() == null ? List.of() : current.getSteps();
        List<String> statuses = current.getStepStatus() == null ? List.of() : current.getStepStatus();
        List<String> notes = current.getNotes() == null ? List.of() : current.getNotes();
        List<List<String>> evidenceRefs = current.getEvidenceRefs() == null
                ? List.of()
                : current.getEvidenceRefs();
        prompt.append("- #").append(index)
                .append(" [").append(valueAt(statuses, index, "not_started")).append("] ")
                .append(compactPromptValue(valueAt(steps, index, "")))
                .append(" | evidence_policy=")
                .append(current.getEvidencePolicyAt(index).name())
                .append(" | activation_id=")
                .append(current.getStepActivationIdAt(index) == null
                        ? "none"
                        : current.getStepActivationIdAt(index))
                .append(" | evidence=")
                .append(summarizeEvidence(index < evidenceRefs.size() ? evidenceRefs.get(index) : List.of()));
        String note = compactPromptValue(valueAt(notes, index, ""));
        if (!note.isBlank()) {
            prompt.append(" | note=").append(note);
        }
        prompt.append('\n');
    }

    private String summarizeEvidence(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return "none";
        }
        StringBuilder summary = new StringBuilder();
        int included = 0;
        int total = 0;
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            total++;
            if (included >= MAX_EVIDENCE_REFS_IN_PROMPT) {
                continue;
            }
            if (included > 0) {
                summary.append(',');
            }
            summary.append(compactPromptValue(ref));
            included++;
        }
        if (total == 0) {
            return "none";
        }
        if (total > included) {
            summary.append(",+").append(total - included).append(" more");
        }
        return summary.toString();
    }

    private String compactPromptValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replace('<', '‹')
                .replace('>', '›')
                .trim()
                .replaceAll(" +", " ");
        if (normalized.length() <= MAX_PROMPT_VALUE_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_PROMPT_VALUE_CHARS - 1) + "…";
    }

    private String valueAt(List<String> values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.size() || values.get(index) == null) {
            return fallback;
        }
        return values.get(index);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private List<String> evidenceRefs(Object value) {
        return new ArrayList<>(new LinkedHashSet<>(stringList(value)));
    }

    private List<TodoEvidencePolicy> evidencePolicies(Object value,
                                                      List<String> steps,
                                                      String command) {
        if (!(value instanceof List<?> items)) {
            if (agentContext != null
                    && agentContext.getExecutionProfile() == AgentExecutionProfile.DEEP) {
                throw new IllegalArgumentException(
                        "evidence_policies must explicitly declare NONE or TOOL for every step in DEEP mode");
            }
            return legacyEvidencePolicies(steps == null ? 0 : steps.size());
        }
        if (steps == null || items.size() != steps.size()) {
            throw new IllegalArgumentException(
                    "evidence_policies must align one-to-one with steps for " + command);
        }
        List<TodoEvidencePolicy> result = new ArrayList<>(items.size());
        for (Object item : items) {
            String normalized = item == null ? "" : String.valueOf(item).trim().toUpperCase();
            if (!"NONE".equals(normalized) && !"TOOL".equals(normalized)) {
                throw new IllegalArgumentException(
                        "evidence_policies only accepts NONE or TOOL for new todo items");
            }
            result.add(TodoEvidencePolicy.valueOf(normalized));
        }
        return result;
    }

    private List<TodoEvidencePolicy> legacyEvidencePolicies(int size) {
        List<TodoEvidencePolicy> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(TodoEvidencePolicy.LEGACY);
        }
        return result;
    }

    private List<String> existingEvidenceRefs(int stepIndex) {
        if (todoList == null || todoList.getEvidenceRefs() == null
                || stepIndex < 0 || stepIndex >= todoList.getEvidenceRefs().size()
                || todoList.getEvidenceRefs().get(stepIndex) == null) {
            return List.of();
        }
        return List.copyOf(todoList.getEvidenceRefs().get(stepIndex));
    }

    private Set<String> allAssignedEvidenceRefs() {
        Set<String> assigned = new LinkedHashSet<>();
        if (todoList == null || todoList.getEvidenceRefs() == null) {
            return assigned;
        }
        for (List<String> refs : todoList.getEvidenceRefs()) {
            if (refs == null) {
                continue;
            }
            refs.stream().filter(ref -> ref != null && !ref.isBlank()).forEach(assigned::add);
        }
        return assigned;
    }

    private List<String> mergeEvidenceRefs(List<String> existingRefs,
                                           List<String> newRefs) {
        Set<String> merged = new LinkedHashSet<>();
        if (existingRefs != null) {
            merged.addAll(existingRefs);
        }
        if (newRefs != null) {
            merged.addAll(newRefs);
        }
        merged.removeIf(ref -> ref == null || ref.isBlank());
        return new ArrayList<>(merged);
    }
}
