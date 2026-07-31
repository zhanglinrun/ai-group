package com.linrun.agent.eval.judge;

/** Judge failures never convert a deterministic failure into a pass. */
public record JudgeOutcome(
        String caseId,
        Status status,
        String verdict,
        String rationale,
        String model,
        String version,
        String promptHash) {
    public enum Status { AVAILABLE, UNAVAILABLE }

    public JudgeOutcome {
        caseId = safe(caseId);
        status = status == null ? Status.UNAVAILABLE : status;
        verdict = safe(verdict);
        rationale = limit(safe(rationale), 500);
        model = safe(model);
        version = safe(version);
        promptHash = safe(promptHash);
    }

    public static JudgeOutcome unavailable(String caseId, String reason) {
        return new JudgeOutcome(caseId, Status.UNAVAILABLE, "NEEDS_HUMAN_REVIEW", reason,
                "", "", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }
}
