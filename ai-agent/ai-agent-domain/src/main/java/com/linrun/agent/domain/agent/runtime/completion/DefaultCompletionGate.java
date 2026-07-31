package com.linrun.agent.domain.agent.runtime.completion;

import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;

import java.util.ArrayList;
import java.util.List;

/** Deterministic completion gate surrounding the unified agent loop. */
public class DefaultCompletionGate implements CompletionGate {

    private static final String NUMERIC_ONLY_PATTERN = "[+-]?\\d+(?:\\.\\d+)?";

    private final FinalVerifier finalVerifier;
    private final EvidenceValidator evidenceValidator;

    public DefaultCompletionGate(FinalVerifier finalVerifier) {
        this(finalVerifier, new DefaultEvidenceValidator());
    }

    public DefaultCompletionGate(FinalVerifier finalVerifier, EvidenceValidator evidenceValidator) {
        this.finalVerifier = finalVerifier;
        this.evidenceValidator = evidenceValidator == null
                ? new DefaultEvidenceValidator()
                : evidenceValidator;
    }

    @Override
    public CompletionDecision evaluate(CompletionRequest request) {
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (request == null) {
            return reject("Completion context is missing.", "Rebuild the completion context and continue.");
        }
        if (request.isRunFailed()) {
            reasons.add("The run contains an execution failure.");
            actions.add("Resolve the failed operation before returning a final answer.");
        }
        if (StringUtils.isBlank(request.getDraftAnswer())) {
            reasons.add("The model did not produce a final answer.");
            actions.add("Produce a concrete answer after completing the remaining work.");
        }

        TodoList todoList = request.getTodoList();
        boolean deep = request.getExecutionProfile() == AgentExecutionProfile.DEEP;
        if (deep && (todoList == null || todoList.getSteps() == null || todoList.getSteps().isEmpty())) {
            reasons.add("Deep execution requires a todo list.");
            actions.add("Create a todo list with todo_write and execute it item by item.");
        }
        if (todoList != null && todoList.getSteps() != null && !todoList.getSteps().isEmpty()
                && (todoList.getStepStatus() == null
                || todoList.getStepStatus().size() != todoList.getSteps().size()
                || todoList.getStepStatus().stream().anyMatch(status -> !"completed".equals(status)))) {
            reasons.add("The todo list is not fully completed.");
            actions.add("Continue the current in-progress todo; do not mark untouched items completed.");
        }

        List<String> missingOutputFields = CompletionOutputContract
                .of(request.getRequiredOutputFields())
                .missingFrom(request.getDraftAnswer());
        if (!missingOutputFields.isEmpty()) {
            reasons.add("The final answer is missing required output fields: "
                    + String.join(", ", missingOutputFields));
            actions.add("Include every required output field by its exact snake_case name in the final answer.");
        }
        if (StringUtils.isNotBlank(request.getRequiredExactFinalAnswer())
                && !StringUtils.equals(
                StringUtils.trim(request.getDraftAnswer()),
                StringUtils.trim(request.getRequiredExactFinalAnswer()))) {
            reasons.add("The final answer must be exactly: " + request.getRequiredExactFinalAnswer());
            actions.add("Reply with exactly this filename and no additional text: "
                    + request.getRequiredExactFinalAnswer());
        }
        if (request.isNumericOnlyFinalAnswer()
                && !StringUtils.trimToEmpty(request.getDraftAnswer()).matches(NUMERIC_ONLY_PATTERN)) {
            reasons.add("The final answer must contain only the computed numeric value.");
            actions.add("Reply with only the computed number and no explanatory text.");
        }

        EvidenceValidator.ValidationResult evidenceResult = evidenceValidator.validate(request);
        reasons.addAll(evidenceResult.reasons());
        actions.addAll(evidenceResult.requiredActions());
        if (request.isReportArtifactRequired() && !request.isReportArtifactPresent()) {
            reasons.add("The requested report artifact was not produced.");
            actions.add("Create the report with report_tool before finishing.");
        }
        if (!reasons.isEmpty()) {
            return CompletionDecision.builder()
                    .canStop(false)
                    .reasons(reasons)
                    .requiredActions(actions)
                    .build();
        }
        if (finalVerifier != null && finalVerifier.supports(request)) {
            return finalVerifier.verify(request);
        }
        return CompletionDecision.allow(false);
    }

    private CompletionDecision reject(String reason, String action) {
        return CompletionDecision.builder()
                .canStop(false)
                .reasons(List.of(reason))
                .requiredActions(List.of(action))
                .build();
    }
}
