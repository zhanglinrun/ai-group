package com.linrun.agent.eval.dataset;

import java.util.List;

public record EvalDataset(String name, String sha256, List<EvalCase> cases) {
    public EvalDataset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("dataset name is required");
        }
        if (sha256 == null || !sha256.startsWith("sha256:")) {
            throw new IllegalArgumentException("dataset sha256 is required");
        }
        cases = List.copyOf(cases == null ? List.of() : cases);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("dataset must contain at least one case");
        }
    }
}
