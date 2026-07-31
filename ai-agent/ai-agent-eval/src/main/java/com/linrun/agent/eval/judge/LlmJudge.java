package com.linrun.agent.eval.judge;

public interface LlmJudge {
    JudgeOutcome judge(JudgeRequest request);
}
