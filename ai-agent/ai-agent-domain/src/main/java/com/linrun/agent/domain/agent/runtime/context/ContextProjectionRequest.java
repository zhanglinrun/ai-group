package com.linrun.agent.domain.agent.runtime.context;

import java.util.List;

/** Inputs available to the projection layer; callers must provide only role-appropriate values. */
public record ContextProjectionRequest(ContextSnapshot snapshot,
                                       ContextRole role,
                                       String currentRequest,
                                       String subtask,
                                       List<String> constraints,
                                       List<String> searchHistory,
                                       List<String> claimEvidence,
                                       List<String> reportOutline,
                                       String reportSpec,
                                       List<String> toolParameters,
                                       int tokenBudget) {

    public ContextProjectionRequest {
        if (snapshot == null) {
            throw new IllegalArgumentException("context snapshot is required");
        }
        role = role == null ? ContextRole.STANDARD : role;
        currentRequest = currentRequest == null ? "" : currentRequest.trim();
        subtask = subtask == null ? "" : subtask.trim();
        constraints = values(constraints);
        searchHistory = values(searchHistory);
        claimEvidence = values(claimEvidence);
        reportOutline = values(reportOutline);
        reportSpec = reportSpec == null ? "" : reportSpec.trim();
        toolParameters = values(toolParameters);
        tokenBudget = Math.max(64, tokenBudget);
    }

    public static ContextProjectionRequest standard(ContextSnapshot snapshot, String currentRequest) {
        return new ContextProjectionRequest(snapshot, ContextRole.STANDARD, currentRequest, "", List.of(), List.of(),
                List.of(), List.of(), "", List.of(), 2_048);
    }

    private static List<String> values(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
