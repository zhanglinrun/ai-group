package com.linrun.agent.eval.evaluator;

import java.util.List;

public record GateResult(boolean passed, List<String> violations) {
    public GateResult {
        violations = List.copyOf(violations == null ? List.of() : violations);
    }
}
