package com.linrun.agent.eval.judge;

/** Default when no dedicated judge endpoint is configured. */
public final class UnavailableJudge implements LlmJudge {
    private final String reason;

    public UnavailableJudge(String reason) {
        this.reason = reason == null || reason.isBlank() ? "Judge is not configured" : reason;
    }

    @Override
    public JudgeOutcome judge(JudgeRequest request) {
        return JudgeOutcome.unavailable(request.caseId(), reason);
    }
}
