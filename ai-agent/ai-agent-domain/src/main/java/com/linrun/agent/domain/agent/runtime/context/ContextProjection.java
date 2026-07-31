package com.linrun.agent.domain.agent.runtime.context;

import java.util.List;

/** Deterministic role-specific input that can be pinned in a Trace or snapshot revision. */
public record ContextProjection(ContextRole role,
                                String rendered,
                                int tokenCount,
                                boolean compacted,
                                long snapshotRevision,
                                String snapshotHash,
                                List<String> retainedLabels) {
    public ContextProjection {
        rendered = rendered == null ? "" : rendered;
        retainedLabels = retainedLabels == null ? List.of() : List.copyOf(retainedLabels);
    }
}
