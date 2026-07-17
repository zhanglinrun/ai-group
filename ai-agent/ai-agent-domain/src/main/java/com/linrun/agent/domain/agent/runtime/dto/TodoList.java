package com.linrun.agent.domain.agent.runtime.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Run-local ordered todo list owned by {@code todo_write}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoList {

    /**
     * Todo list title.
     */
    private String title;

    /**
     * Ordered todo items.
     */
    private List<String> steps;

    /**
     * Item status list, aligned by index with {@link #steps}.
     */
    private List<String> stepStatus;

    /**
     * Item notes, aligned by index with {@link #steps}.
     */
    private List<String> notes;

    /** Successful tool-call ids that prove each item was completed. */
    private List<List<String>> evidenceRefs;

    /** Per-item evidence contract, aligned by index with {@link #steps}. */
    private List<TodoEvidencePolicy> evidencePolicies;

    /**
     * Per-item activation identity. A business tool call is valid only for the
     * item whose activation id was current when the call began.
     */
    private List<Long> stepActivationIds;

    /**
     * Create an empty snapshot for idempotent terminal operations.
     */
    public static TodoList empty() {
        return TodoList.builder()
                .title("")
                .steps(new ArrayList<>())
                .stepStatus(new ArrayList<>())
                .notes(new ArrayList<>())
                .evidenceRefs(new ArrayList<>())
                .evidencePolicies(new ArrayList<>())
                .stepActivationIds(new ArrayList<>())
                .build();
    }

    /**
     * Create a new todo list.
     */
    public static TodoList create(String title, List<String> steps) {
        return create(title, steps, legacyPolicies(steps == null ? 0 : steps.size()));
    }

    /** Create a new todo list with an explicit evidence contract per item. */
    public static TodoList create(String title,
                                  List<String> steps,
                                  List<TodoEvidencePolicy> evidencePolicies) {
        List<String> status = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<List<String>> evidenceRefs = new ArrayList<>();
        List<TodoEvidencePolicy> policies = new ArrayList<>();
        List<Long> activationIds = new ArrayList<>();

        for (int i = 0; i < steps.size(); i++) {
            status.add("not_started");
            notes.add("");
            evidenceRefs.add(new ArrayList<>());
            policies.add(policyAt(evidencePolicies, i));
            activationIds.add(null);
        }

        return TodoList.builder()
                .title(title)
                .steps(steps)
                .stepStatus(status)
                .notes(notes)
                .evidenceRefs(evidenceRefs)
                .evidencePolicies(policies)
                .stepActivationIds(activationIds)
                .build();
    }

    /**
     * Deep-copy the todo snapshot so persistence and replay never share a mutable reference.
     */
    public TodoList copy() {
        return TodoList.builder()
                .title(title)
                .steps(copyList(steps))
                .stepStatus(copyList(stepStatus))
                .notes(copyList(notes))
                .evidenceRefs(copyNestedList(evidenceRefs))
                .evidencePolicies(copyPolicyList(evidencePolicies))
                .stepActivationIds(copyLongList(stepActivationIds))
                .build();
    }

    /**
     * Replace todo content while preserving status and notes at unchanged indexes.
     */
    public void update(String title, List<String> newSteps) {
        if (title != null) {
            this.title = title;
        }

        if (newSteps != null) {
            List<String> newStatuses = new ArrayList<>();
            List<String> newNotes = new ArrayList<>();
            List<List<String>> newEvidenceRefs = new ArrayList<>();
            List<TodoEvidencePolicy> newEvidencePolicies = new ArrayList<>();
            List<Long> newStepActivationIds = new ArrayList<>();

            for (int i = 0; i < newSteps.size(); i++) {
                if (i < this.steps.size() && newSteps.get(i).equals(this.steps.get(i))) {
                    newStatuses.add(this.stepStatus.get(i));
                    newNotes.add(this.notes.get(i));
                    newEvidenceRefs.add(copyEvidenceRefsAt(i));
                    newEvidencePolicies.add(getEvidencePolicyAt(i));
                    newStepActivationIds.add(getStepActivationIdAt(i));
                } else {
                    newStatuses.add("not_started");
                    newNotes.add("");
                    newEvidenceRefs.add(new ArrayList<>());
                    newEvidencePolicies.add(TodoEvidencePolicy.LEGACY);
                    newStepActivationIds.add(null);
                }
            }

            this.steps = newSteps;
            this.stepStatus = newStatuses;
            this.notes = newNotes;
            this.evidenceRefs = newEvidenceRefs;
            this.evidencePolicies = newEvidencePolicies;
            this.stepActivationIds = newStepActivationIds;
        }
    }

    /**
     * Update one todo item's status and note.
     */
    public void updateStepStatus(int stepIndex, String status, String note) {
        if (stepIndex < 0 || stepIndex >= steps.size()) {
            throw new IllegalArgumentException("Invalid step index: " + stepIndex);
        }

        if (status != null) {
            this.stepStatus.set(stepIndex, status);
        }

        if (note != null) {
            this.notes.set(stepIndex, note);
        }
    }

    /** Replace the evidence refs associated with one todo item. */
    public void updateEvidenceRefs(int stepIndex, List<String> refs) {
        if (stepIndex < 0 || stepIndex >= steps.size()) {
            throw new IllegalArgumentException("Invalid step index: " + stepIndex);
        }
        while (evidenceRefs == null || evidenceRefs.size() < steps.size()) {
            if (evidenceRefs == null) {
                evidenceRefs = new ArrayList<>();
            }
            evidenceRefs.add(new ArrayList<>());
        }
        evidenceRefs.set(stepIndex, refs == null ? new ArrayList<>() : new ArrayList<>(refs));
    }

    /** Resolve a policy without failing on historical snapshots that lack the field. */
    @JsonIgnore
    public TodoEvidencePolicy getEvidencePolicyAt(int stepIndex) {
        return policyAt(evidencePolicies, stepIndex);
    }

    /** Resolve an activation id without failing on historical snapshots. */
    @JsonIgnore
    public Long getStepActivationIdAt(int stepIndex) {
        if (stepActivationIds == null || stepIndex < 0 || stepIndex >= stepActivationIds.size()) {
            return null;
        }
        return stepActivationIds.get(stepIndex);
    }

    /**
     * Return the current in-progress todo item.
     */
    @JsonIgnore
    public String getCurrentStep() {
        for (int i = 0; i < steps.size(); i++) {
            if ("in_progress".equals(stepStatus.get(i))) {
                return steps.get(i);
            }
        }
        return "";
    }

    /**
     * Return the index of the current in-progress todo item.
     */
    @JsonIgnore
    public Integer getCurrentStepIndex() {
        if (steps == null || stepStatus == null) {
            return null;
        }
        for (int i = 0; i < steps.size() && i < stepStatus.size(); i++) {
            if ("in_progress".equals(stepStatus.get(i))) {
                return i;
            }
        }
        return null;
    }

    /**
     * Complete the active item and advance to the next one.
     */
    public void advance() {
        if (steps.isEmpty()) {
            return;
        }
        if (getCurrentStep().isEmpty()) {
            updateStepStatus(0, "in_progress", "");
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            if ("in_progress".equals(stepStatus.get(i))) {
                updateStepStatus(i, "completed", "");
                if (i + 1 < steps.size()) {
                    updateStepStatus(i + 1, "in_progress", "");
                    break;
                }
            }
        }
    }

    /**
     * Format the todo list for diagnostics.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();

        sb.append("Todo list: ").append(title).append("\n");
        sb.append("Items:\n");
        for (int i = 0; i < steps.size(); i++) {
            String status = stepStatus.get(i);
            String step = steps.get(i);
            String note = notes.get(i);
            sb.append(String.format("%d. [%s] %s\n", i + 1, status, step));

            if (note != null && !note.isEmpty()) {
                sb.append("   Note: ").append(note).append("\n");
            }
        }

        return sb.toString();
    }

    private List<String> copyList(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private List<List<String>> copyNestedList(List<List<String>> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        List<List<String>> copy = new ArrayList<>(source.size());
        for (List<String> refs : source) {
            copy.add(refs == null ? new ArrayList<>() : new ArrayList<>(refs));
        }
        return copy;
    }

    private List<TodoEvidencePolicy> copyPolicyList(List<TodoEvidencePolicy> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private List<Long> copyLongList(List<Long> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private List<String> copyEvidenceRefsAt(int index) {
        if (evidenceRefs == null || index < 0 || index >= evidenceRefs.size() || evidenceRefs.get(index) == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(evidenceRefs.get(index));
    }

    private static List<TodoEvidencePolicy> legacyPolicies(int size) {
        List<TodoEvidencePolicy> policies = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            policies.add(TodoEvidencePolicy.LEGACY);
        }
        return policies;
    }

    private static TodoEvidencePolicy policyAt(List<TodoEvidencePolicy> policies, int index) {
        if (policies == null || index < 0 || index >= policies.size() || policies.get(index) == null) {
            return TodoEvidencePolicy.LEGACY;
        }
        return policies.get(index);
    }
}
