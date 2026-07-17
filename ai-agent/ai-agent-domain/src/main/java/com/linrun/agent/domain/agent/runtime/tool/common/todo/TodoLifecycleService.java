package com.linrun.agent.domain.agent.runtime.tool.common.todo;

import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Lifecycle service for the run-local todo list.
 * It centralizes create/update/mark_step/finish validation, repair, and automatic advancement.
 */
public class TodoLifecycleService {

    private static final String STATUS_NOT_STARTED = "not_started";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_BLOCKED = "blocked";

    /** Create a todo list and activate its first executable item. */
    public TodoLifecycleResult create(String title, List<String> steps) {
        return create(title, steps, legacyPolicies(steps));
    }

    /** Create a todo list with one explicit evidence contract per item. */
    public TodoLifecycleResult create(String title,
                                      List<String> steps,
                                      List<TodoEvidencePolicy> evidencePolicies) {
        validateNonEmptySteps(steps);
        validateEvidencePolicies(steps, evidencePolicies);
        TodoList todoList = TodoList.create(title, copySteps(steps), evidencePolicies);
        activateFirstNotStarted(todoList);
        return buildResult(todoList, true, false);
    }

    /** Replace the unfinished suffix while freezing the completed prefix. */
    public TodoLifecycleResult update(TodoList todoList, String title, List<String> remainingSteps) {
        return update(todoList, title, remainingSteps, legacyPolicies(remainingSteps));
    }

    /** Replace the unfinished suffix together with its evidence contracts. */
    public TodoLifecycleResult update(TodoList todoList,
                                      String title,
                                      List<String> remainingSteps,
                                      List<TodoEvidencePolicy> remainingEvidencePolicies) {
        validateTodoListExists(todoList);
        normalizeTodoList(todoList);

        if (StringUtils.isNotBlank(title)) {
            todoList.setTitle(title);
        }
        if (remainingSteps == null) {
            return ensureExecutable(todoList);
        }
        validateNonEmptySteps(remainingSteps);
        validateEvidencePolicies(remainingSteps, remainingEvidencePolicies);

        // Capture the next activation before replacing the unfinished suffix.
        // Otherwise discarded activation ids disappear from the snapshot and
        // nextActivationId(todoList) can reuse an identity that already scoped
        // tool evidence earlier in this run.
        long replacementActivationId = nextActivationId(todoList);

        int completedPrefixSize = countCompletedPrefix(todoList);
        List<String> mergedSteps = new ArrayList<>();
        List<String> mergedStatus = new ArrayList<>();
        List<String> mergedNotes = new ArrayList<>();
        List<List<String>> mergedEvidenceRefs = new ArrayList<>();
        List<TodoEvidencePolicy> mergedEvidencePolicies = new ArrayList<>();
        List<Long> mergedStepActivationIds = new ArrayList<>();

        for (int index = 0; index < completedPrefixSize; index++) {
            mergedSteps.add(todoList.getSteps().get(index));
            mergedStatus.add(STATUS_COMPLETED);
            mergedNotes.add(todoList.getNotes().get(index));
            mergedEvidenceRefs.add(new ArrayList<>(todoList.getEvidenceRefs().get(index)));
            mergedEvidencePolicies.add(todoList.getEvidencePolicyAt(index));
            mergedStepActivationIds.add(todoList.getStepActivationIdAt(index));
        }
        for (int index = 0; index < remainingSteps.size(); index++) {
            String step = remainingSteps.get(index);
            mergedSteps.add(step);
            mergedStatus.add(STATUS_NOT_STARTED);
            mergedNotes.add("");
            mergedEvidenceRefs.add(new ArrayList<>());
            mergedEvidencePolicies.add(remainingEvidencePolicies.get(index));
            mergedStepActivationIds.add(null);
        }

        todoList.setSteps(mergedSteps);
        todoList.setStepStatus(mergedStatus);
        todoList.setNotes(mergedNotes);
        todoList.setEvidenceRefs(mergedEvidenceRefs);
        todoList.setEvidencePolicies(mergedEvidencePolicies);
        todoList.setStepActivationIds(mergedStepActivationIds);

        boolean autoAdvanced = activateFirstNotStarted(todoList, replacementActivationId);
        return buildResult(todoList, autoAdvanced, false);
    }

    /** Mark one item and automatically advance when the active item completes. */
    public TodoLifecycleResult markStep(TodoList todoList, Integer stepIndex, String status, String note) {
        validateTodoListExists(todoList);
        normalizeTodoList(todoList);
        validateStepIndex(todoList, stepIndex);
        validateStepStatus(status);

        String currentStatus = todoList.getStepStatus().get(stepIndex);
        if (STATUS_COMPLETED.equals(currentStatus) && !StringUtils.equals(status, STATUS_COMPLETED)) {
            throw new IllegalStateException("completed todo item is frozen and cannot be mutated");
        }

        Integer currentStepIndex = todoList.getCurrentStepIndex();
        if (STATUS_IN_PROGRESS.equals(status)
                && currentStepIndex != null
                && !currentStepIndex.equals(stepIndex)) {
            throw new IllegalStateException("only one todo item can be in_progress");
        }
        if (STATUS_COMPLETED.equals(status)
                && currentStepIndex != null
                && !currentStepIndex.equals(stepIndex)) {
            throw new IllegalStateException("only the current todo item can be completed");
        }

        todoList.updateStepStatus(stepIndex, status, note);

        if (!STATUS_COMPLETED.equals(status)) {
            return ensureExecutable(todoList);
        }

        if (isAllStepsCompleted(todoList)) {
            return buildResult(todoList, false, true);
        }

        boolean autoAdvanced = activateFirstNotStarted(todoList);
        return buildResult(todoList, autoAdvanced, false);
    }

    /**
     * Validate an explicit finish request. Finishing never mutates untouched items:
     * every item must have been completed individually first.
     */
    public TodoLifecycleResult finish(TodoList todoList) {
        validateTodoListExists(todoList);
        normalizeTodoList(todoList);
        if (!isAllStepsCompleted(todoList)) {
            throw new IllegalStateException("cannot finish a todo list with pending, in_progress, or blocked items");
        }
        return buildResult(todoList, false, true);
    }

    /** Repair a missing current item in a non-terminal todo list when that repair is unambiguous. */
    public TodoLifecycleResult ensureExecutable(TodoList todoList) {
        validateTodoListExists(todoList);
        normalizeTodoList(todoList);
        repairMultipleInProgress(todoList);

        if (isAllStepsCompleted(todoList)) {
            return buildResult(todoList, false, true);
        }
        if (todoList.getCurrentStepIndex() != null) {
            return buildResult(todoList, false, false);
        }

        boolean repaired = activateFirstNotStarted(todoList);
        if (!repaired) {
            throw new IllegalStateException("current todo item is missing and cannot be repaired");
        }
        return buildResult(todoList, true, false);
    }

    /** Return whether every todo item is completed. */
    public boolean isAllStepsCompleted(TodoList todoList) {
        if (todoList == null || todoList.getSteps() == null || todoList.getSteps().isEmpty()) {
            return true;
        }
        normalizeTodoList(todoList);
        return todoList.getStepStatus().stream().allMatch(STATUS_COMPLETED::equals);
    }

    private void validateTodoListExists(TodoList todoList) {
        if (todoList == null) {
            throw new IllegalStateException("No todo list exists. Create one first.");
        }
    }

    private void validateNonEmptySteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("todo items cannot be empty");
        }
        for (String step : steps) {
            if (StringUtils.isBlank(step)) {
                throw new IllegalArgumentException("todo item cannot be blank");
            }
        }
    }

    private void validateEvidencePolicies(List<String> steps,
                                          List<TodoEvidencePolicy> evidencePolicies) {
        if (steps == null || evidencePolicies == null || evidencePolicies.size() != steps.size()) {
            throw new IllegalArgumentException("evidence policies must align one-to-one with todo items");
        }
        if (evidencePolicies.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("todo evidence policy cannot be null");
        }
    }

    private void validateStepIndex(TodoList todoList, Integer stepIndex) {
        if (stepIndex == null) {
            throw new IllegalArgumentException("step_index is required for mark_step command");
        }
        if (stepIndex < 0 || stepIndex >= todoList.getSteps().size()) {
            throw new IllegalArgumentException("Invalid step index: " + stepIndex);
        }
    }

    private void validateStepStatus(String status) {
        if (!STATUS_NOT_STARTED.equals(status)
                && !STATUS_IN_PROGRESS.equals(status)
                && !STATUS_COMPLETED.equals(status)
                && !STATUS_BLOCKED.equals(status)) {
            throw new IllegalArgumentException("Invalid step status: " + status);
        }
    }

    /** Normalize manually constructed snapshots so aligned lists cannot drift. */
    private void normalizeTodoList(TodoList todoList) {
        List<String> steps = todoList.getSteps() == null ? new ArrayList<>() : new ArrayList<>(todoList.getSteps());
        List<String> status = todoList.getStepStatus() == null ? new ArrayList<>() : new ArrayList<>(todoList.getStepStatus());
        List<String> notes = todoList.getNotes() == null ? new ArrayList<>() : new ArrayList<>(todoList.getNotes());
        List<List<String>> evidenceRefs = copyEvidenceRefs(todoList.getEvidenceRefs());
        List<TodoEvidencePolicy> evidencePolicies = todoList.getEvidencePolicies() == null
                ? new ArrayList<>()
                : new ArrayList<>(todoList.getEvidencePolicies());
        List<Long> stepActivationIds = todoList.getStepActivationIds() == null
                ? new ArrayList<>()
                : new ArrayList<>(todoList.getStepActivationIds());

        while (status.size() < steps.size()) {
            status.add(STATUS_NOT_STARTED);
        }
        while (notes.size() < steps.size()) {
            notes.add("");
        }
        while (evidenceRefs.size() < steps.size()) {
            evidenceRefs.add(new ArrayList<>());
        }
        while (evidencePolicies.size() < steps.size()) {
            evidencePolicies.add(TodoEvidencePolicy.LEGACY);
        }
        while (stepActivationIds.size() < steps.size()) {
            stepActivationIds.add(null);
        }
        if (status.size() > steps.size()) {
            status = new ArrayList<>(status.subList(0, steps.size()));
        }
        if (notes.size() > steps.size()) {
            notes = new ArrayList<>(notes.subList(0, steps.size()));
        }
        if (evidenceRefs.size() > steps.size()) {
            evidenceRefs = new ArrayList<>(evidenceRefs.subList(0, steps.size()));
        }
        if (evidencePolicies.size() > steps.size()) {
            evidencePolicies = new ArrayList<>(evidencePolicies.subList(0, steps.size()));
        }
        if (stepActivationIds.size() > steps.size()) {
            stepActivationIds = new ArrayList<>(stepActivationIds.subList(0, steps.size()));
        }
        for (int index = 0; index < evidencePolicies.size(); index++) {
            if (evidencePolicies.get(index) == null) {
                evidencePolicies.set(index, TodoEvidencePolicy.LEGACY);
            }
        }

        todoList.setSteps(steps);
        todoList.setStepStatus(status);
        todoList.setNotes(notes);
        todoList.setEvidenceRefs(evidenceRefs);
        todoList.setEvidencePolicies(evidencePolicies);
        todoList.setStepActivationIds(stepActivationIds);

        Integer currentStepIndex = todoList.getCurrentStepIndex();
        if (currentStepIndex != null
                && todoList.getEvidencePolicyAt(currentStepIndex) != TodoEvidencePolicy.LEGACY
                && todoList.getStepActivationIdAt(currentStepIndex) == null) {
            todoList.getStepActivationIds().set(currentStepIndex, nextActivationId(todoList));
        }
    }

    /** Repair historical snapshots while all new invalid mutations are rejected strictly. */
    private void repairMultipleInProgress(TodoList todoList) {
        boolean found = false;
        for (int index = 0; index < todoList.getStepStatus().size(); index++) {
            if (!STATUS_IN_PROGRESS.equals(todoList.getStepStatus().get(index))) {
                continue;
            }
            if (!found) {
                found = true;
            } else {
                todoList.getStepStatus().set(index, STATUS_NOT_STARTED);
                todoList.getStepActivationIds().set(index, null);
            }
        }
    }

    /** Activate the first not-started item and clear any stale active item. */
    private boolean activateFirstNotStarted(TodoList todoList) {
        return activateFirstNotStarted(todoList, nextActivationId(todoList));
    }

    private boolean activateFirstNotStarted(TodoList todoList, long activationId) {
        Integer nextIndex = null;
        for (int index = 0; index < todoList.getStepStatus().size(); index++) {
            String status = todoList.getStepStatus().get(index);
            if (STATUS_NOT_STARTED.equals(status)) {
                nextIndex = index;
                break;
            }
        }
        if (nextIndex == null) {
            return false;
        }
        for (int index = 0; index < todoList.getStepStatus().size(); index++) {
            if (STATUS_IN_PROGRESS.equals(todoList.getStepStatus().get(index))) {
                todoList.getStepStatus().set(index, STATUS_NOT_STARTED);
            }
        }
        todoList.getStepStatus().set(nextIndex, STATUS_IN_PROGRESS);
        todoList.getStepActivationIds().set(nextIndex, activationId);
        return true;
    }

    private int countCompletedPrefix(TodoList todoList) {
        int count = 0;
        for (String status : todoList.getStepStatus()) {
            if (!STATUS_COMPLETED.equals(status)) {
                break;
            }
            count++;
        }
        return count;
    }

    private List<String> copySteps(List<String> steps) {
        return steps == null ? List.of() : new ArrayList<>(steps);
    }

    private List<List<String>> copyEvidenceRefs(List<List<String>> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        List<List<String>> copy = new ArrayList<>(source.size());
        for (List<String> refs : source) {
            copy.add(refs == null ? new ArrayList<>() : new ArrayList<>(refs));
        }
        return copy;
    }

    private List<TodoEvidencePolicy> legacyPolicies(List<String> steps) {
        int size = steps == null ? 0 : steps.size();
        List<TodoEvidencePolicy> policies = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            policies.add(TodoEvidencePolicy.LEGACY);
        }
        return policies;
    }

    private long nextActivationId(TodoList todoList) {
        long maximum = 0L;
        if (todoList != null && todoList.getStepActivationIds() != null) {
            for (Long activationId : todoList.getStepActivationIds()) {
                if (activationId != null && activationId > maximum) {
                    maximum = activationId;
                }
            }
        }
        return maximum + 1L;
    }

    private TodoLifecycleResult buildResult(TodoList todoList, boolean autoAdvanced, boolean autoFinished) {
        return TodoLifecycleResult.builder()
                .todoList(todoList)
                .currentStep(todoList == null ? "" : todoList.getCurrentStep())
                .currentStepIndex(todoList == null ? null : todoList.getCurrentStepIndex())
                .autoAdvanced(autoAdvanced)
                .autoFinished(autoFinished)
                .build();
    }
}
