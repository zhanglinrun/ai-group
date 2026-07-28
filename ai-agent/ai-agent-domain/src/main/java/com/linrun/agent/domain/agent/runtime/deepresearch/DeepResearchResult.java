package com.linrun.agent.domain.agent.runtime.deepresearch;

import java.util.List;
import java.util.Map;

public record DeepResearchResult(String summary,
                                 String markdown,
                                 String reportArtifactId,
                                 String qualityStatus,
                                 double citationCoverage,
                                 int sourceCount,
                                 int charCount,
                                 int repairCount,
                                 List<Map<String, Object>> artifactRefs,
                                 boolean completed) {

    @SuppressWarnings("unchecked")
    public static DeepResearchResult from(DeepResearchState state) {
        ReportQualityResult quality = state.quality();
        Object refs = state.data().get(DeepResearchState.ARTIFACT_REFS);
        List<Map<String, Object>> artifactRefs = refs instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        return new DeepResearchResult(
                state.text(DeepResearchState.SUMMARY),
                state.text(DeepResearchState.MARKDOWN),
                state.text(DeepResearchState.REPORT_ARTIFACT_ID),
                quality.status(),
                quality.citationCoverage(),
                quality.sourceCount(),
                quality.charCount(),
                state.integer(DeepResearchState.REPAIR_COUNT),
                artifactRefs,
                !state.text(DeepResearchState.REPORT_ARTIFACT_ID).isBlank()
        );
    }
}
