package com.linrun.agent.domain.agent.runtime.completion;

import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.agent.ExplicitToolChoicePolicy;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Default typed evidence policy shared by Todo and the final completion gate. */
public class DefaultEvidenceValidator implements EvidenceValidator {

    @Override
    public ValidationResult validate(CompletionRequest request) {
        if (request == null) {
            return ValidationResult.valid();
        }
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<ToolExecutionEvidence> evidence = request.getToolEvidence();
        Map<String, ToolExecutionEvidence> latestEvidence = latestEvidenceByOperation(evidence);
        if (!latestEvidence.isEmpty()) {
            for (ToolExecutionEvidence failed : latestEvidence.values()) {
                if (failed != null && !failed.isSuccess()
                        && !isCorrectedInputFailure(failed, evidence)) {
                    reasons.add("An unresolved tool call failed: "
                            + StringUtils.defaultString(failed.getToolName(), "unknown"));
                    actions.add("Retry that capability successfully or resolve its failure before finishing.");
                }
            }
        }
        if (request.isNetworkLookupRequired() && latestEvidence.values().stream()
                .noneMatch(item -> item != null
                        && item.isSuccess()
                        && ExplicitToolChoicePolicy.isNetworkLookupToolName(item.getToolName()))) {
            reasons.add("The user required network lookup, but no network lookup completed successfully.");
            actions.add("Use an available network search/fetch capability successfully; if none is available, fail explicitly.");
        }
        if (StringUtils.isNotBlank(request.getRequiredToolName())
                && (evidence == null || evidence.stream().noneMatch(item -> item != null
                && item.isSuccess()
                && StringUtils.equals(request.getRequiredToolName(), item.getToolName())))) {
            reasons.add("The explicitly required tool did not complete successfully: "
                    + request.getRequiredToolName());
            actions.add("Call the explicitly required tool successfully before finishing: "
                    + request.getRequiredToolName());
        }
        ToolInvocationContract invocationContract = request.getToolInvocationContract();
        if (invocationContract != null && invocationContract.constrained()) {
            Set<String> disallowedSuccessfulTools = new LinkedHashSet<>();
            if (evidence != null) {
                for (ToolExecutionEvidence item : evidence) {
                    if (item != null && item.isSuccess()
                            && !invocationContract.allows(item.getToolName())) {
                        disallowedSuccessfulTools.add(
                                StringUtils.defaultString(item.getToolName(), "unknown"));
                    }
                }
            }
            if (!disallowedSuccessfulTools.isEmpty()) {
                reasons.add("Successful evidence used tools outside the user invocation contract: "
                        + String.join(", ", disallowedSuccessfulTools));
                actions.add("Discard forbidden or outside-exclusive tool results and rerun using only allowed tools.");
            }
        }

        TodoList todoList = request.getTodoList();
        if (todoList != null && !hasValidTodoEvidenceAssignments(
                todoList, evidence, requiresTypedTodoEvidence(request, evidence))) {
            reasons.add("Completed todo items are missing verified tool evidence.");
            actions.add("For NONE items remove business evidence; for TOOL items attach one fresh successful non-reused toolCallId from that item's activation only.");
        }
        return new ValidationResult(reasons, actions);
    }

    private boolean isCorrectedInputFailure(ToolExecutionEvidence failure,
                                            List<ToolExecutionEvidence> evidence) {
        if (failure == null || !failure.isCorrectableInputFailure()
                || evidence == null || evidence.isEmpty()) {
            return false;
        }
        int failureIndex = evidence.indexOf(failure);
        for (int index = failureIndex + 1; index < evidence.size(); index++) {
            ToolExecutionEvidence candidate = evidence.get(index);
            if (candidate != null
                    && candidate.isSuccess()
                    && !candidate.isReused()
                    && StringUtils.equals(failure.getToolName(), candidate.getToolName())
                    && java.util.Objects.equals(
                    failure.getTodoStepIndex(), candidate.getTodoStepIndex())
                    && java.util.Objects.equals(
                    failure.getTodoStepActivationId(), candidate.getTodoStepActivationId())) {
                return true;
            }
        }
        return false;
    }

    public boolean isSuccessfulBusinessEvidence(String toolCallId,
                                                List<ToolExecutionEvidence> evidence) {
        if (StringUtils.isBlank(toolCallId) || evidence == null) {
            return false;
        }
        return evidence.stream().anyMatch(item -> item != null
                && item.isSuccess()
                && !item.isReused()
                && !"todo_write".equals(item.getToolName())
                && toolCallId.equals(item.getToolCallId()));
    }

    /** Strict evidence check for one explicitly-scoped TOOL Todo item. */
    public boolean isSuccessfulBusinessEvidenceForStep(String toolCallId,
                                                       List<ToolExecutionEvidence> evidence,
                                                       int todoStepIndex,
                                                       Long todoStepActivationId) {
        if (StringUtils.isBlank(toolCallId) || todoStepActivationId == null || evidence == null) {
            return false;
        }
        return evidence.stream().anyMatch(item -> item != null
                && item.isSuccess()
                && !item.isReused()
                && !"todo_write".equals(item.getToolName())
                && toolCallId.equals(item.getToolCallId())
                && item.getTodoStepIndex() != null
                && item.getTodoStepActivationId() != null
                && item.getTodoStepIndex() == todoStepIndex
                && item.getTodoStepActivationId().equals(todoStepActivationId));
    }

    private Map<String, ToolExecutionEvidence> latestEvidenceByOperation(List<ToolExecutionEvidence> evidence) {
        Map<String, ToolExecutionEvidence> latest = new LinkedHashMap<>();
        if (evidence == null || evidence.isEmpty()) {
            return latest;
        }
        for (ToolExecutionEvidence item : evidence) {
            if (item == null) {
                continue;
            }
            String key = StringUtils.defaultIfBlank(item.getOperationKey(), item.getToolCallId());
            if (StringUtils.isNotBlank(key)) {
                latest.put(key, item);
            }
        }
        return latest;
    }

    private boolean requiresTypedTodoEvidence(CompletionRequest request,
                                              List<ToolExecutionEvidence> evidence) {
        if (request.getExecutionProfile() == AgentExecutionProfile.DEEP) {
            return true;
        }
        return evidence != null && evidence.stream().anyMatch(item -> item != null
                && !"todo_write".equals(item.getToolName()));
    }

    private boolean hasValidTodoEvidenceAssignments(TodoList todoList,
                                                    List<ToolExecutionEvidence> evidence,
                                                    boolean legacyEvidenceRequired) {
        List<String> steps = todoList.getSteps();
        List<String> statuses = todoList.getStepStatus();
        List<List<String>> refs = todoList.getEvidenceRefs();
        if (steps == null || steps.isEmpty() || statuses == null || statuses.size() != steps.size()
                || refs == null || refs.size() != steps.size()) {
            return false;
        }
        Set<String> successfulBusinessCalls = new LinkedHashSet<>();
        if (evidence != null) {
            for (ToolExecutionEvidence item : evidence) {
                if (item != null && item.isSuccess()
                        && !item.isReused()
                        && !"todo_write".equals(item.getToolName())
                        && StringUtils.isNotBlank(item.getToolCallId())) {
                    successfulBusinessCalls.add(item.getToolCallId());
                }
            }
        }
        Set<String> strictlyConsumedCalls = new LinkedHashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            if (!"completed".equals(statuses.get(index))) {
                continue;
            }
            List<String> stepRefs = refs.get(index);
            TodoEvidencePolicy evidencePolicy = todoList.getEvidencePolicyAt(index);
            if (evidencePolicy == TodoEvidencePolicy.NONE) {
                if (stepRefs != null && !stepRefs.isEmpty()) {
                    return false;
                }
                Long activationId = todoList.getStepActivationIdAt(index);
                int stepIndex = index;
                if (activationId != null && evidence != null && evidence.stream().anyMatch(item -> item != null
                        && item.isSuccess()
                        && !item.isReused()
                        && !"todo_write".equals(item.getToolName())
                        && item.getTodoStepIndex() != null
                        && item.getTodoStepActivationId() != null
                        && item.getTodoStepIndex() == stepIndex
                        && item.getTodoStepActivationId().equals(activationId))) {
                    return false;
                }
                continue;
            }
            if (evidencePolicy == TodoEvidencePolicy.TOOL) {
                if (stepRefs == null || stepRefs.isEmpty()) {
                    return false;
                }
                Long activationId = todoList.getStepActivationIdAt(index);
                for (String ref : stepRefs) {
                    if (!strictlyConsumedCalls.add(ref)
                            || !isSuccessfulBusinessEvidenceForStep(
                            ref, evidence, index, activationId)) {
                        return false;
                    }
                }
                continue;
            }
            if (legacyEvidenceRequired
                    && (stepRefs == null || stepRefs.isEmpty()
                    || stepRefs.stream().noneMatch(successfulBusinessCalls::contains))) {
                return false;
            }
        }
        return true;
    }
}
