package com.linrun.agent.domain.agent.runtime.tool.common;

import java.util.List;

/** Stable, small tool result for every attachment-analysis path. It never contains raw binary/base64. */
public record FileAnalysisResult(String fileName,
                                 String strategy,
                                 String answer,
                                 String uncertainty,
                                 boolean degraded,
                                 List<String> evidence,
                                 String artifactReference) {
    public FileAnalysisResult {
        fileName = fileName == null ? "" : fileName;
        strategy = strategy == null ? "" : strategy;
        answer = answer == null ? "" : answer;
        uncertainty = uncertainty == null ? "" : uncertainty;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        artifactReference = artifactReference == null ? "" : artifactReference;
    }
}
