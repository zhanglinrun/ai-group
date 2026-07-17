package com.linrun.agent.domain.agent.runtime.completion;

import java.util.List;

/** Validates typed tool evidence without inferring completion from prose. */
public interface EvidenceValidator {

    ValidationResult validate(CompletionRequest request);

    record ValidationResult(List<String> reasons, List<String> requiredActions) {
        public ValidationResult {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
        }

        public static ValidationResult valid() {
            return new ValidationResult(List.of(), List.of());
        }
    }
}
