package com.linrun.agent.eval.dataset;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public final class DatasetCatalog {
    public static final List<String> GOLDEN_RESOURCES = List.of(
            "datasets/standard_basic.jsonl",
            "datasets/deep_research.jsonl",
            "datasets/file_analysis.jsonl",
            "datasets/mcp_security.jsonl",
            "datasets/memory_compaction.jsonl",
            "datasets/recovery_resume.jsonl",
            "datasets/quota_idempotency.jsonl",
            "datasets/adversarial_prompt_injection.jsonl");

    private DatasetCatalog() {
    }

    public static EvalDataset loadDefault() throws IOException {
        return new DatasetLoader().loadClasspath(GOLDEN_RESOURCES);
    }

    public static EvalDataset select(EvalDataset dataset, Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return dataset;
        }
        List<EvalCase> selected = dataset.cases().stream().filter(evalCase -> ids.contains(evalCase.id())).toList();
        if (selected.size() != ids.size()) {
            Set<String> found = selected.stream().map(EvalCase::id).collect(java.util.stream.Collectors.toSet());
            throw new IllegalArgumentException("unknown eval case ids: " + ids.stream().filter(id -> !found.contains(id)).toList());
        }
        return new EvalDataset(dataset.name() + "-selected", dataset.sha256(), selected);
    }
}
