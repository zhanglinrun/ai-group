package com.linrun.agent.domain.agent.runtime.deepresearch;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A bounded, question-driven unit of Deep Research work. */
public record ResearchSubtask(String id,
                              String section,
                              String objective,
                              List<String> allowedTools,
                              int maxToolCalls,
                              String outputSchema,
                              int minimumEvidence,
                              List<String> queryTerms,
                              List<String> evidenceRequirements,
                              String evidenceCutoff,
                              String evaluationCriteria) {

    /** Compatibility constructor for plans persisted before P40. */
    public ResearchSubtask(String id,
                           String section,
                           String objective,
                           List<String> allowedTools,
                           int maxToolCalls,
                           String outputSchema,
                           int minimumEvidence) {
        this(id, section, objective, allowedTools, maxToolCalls, outputSchema, minimumEvidence,
                List.of(StringUtils.defaultIfBlank(objective, section)),
                List.of("每个正式结论必须绑定真实 HTTP(S) URL、标题、摘录与 claimId"),
                "截至本次运行开始时可验证的公开资料",
                "证据数量、来源可追溯性、冲突与不确定性必须显式输出");
    }

    public ResearchSubtask {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        queryTerms = queryTerms == null ? List.of() : List.copyOf(queryTerms);
        evidenceRequirements = evidenceRequirements == null ? List.of() : List.copyOf(evidenceRequirements);
        evidenceCutoff = StringUtils.defaultString(evidenceCutoff);
        evaluationCriteria = StringUtils.defaultString(evaluationCriteria);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("section", section);
        map.put("objective", objective);
        map.put("allowedTools", allowedTools);
        map.put("maxToolCalls", maxToolCalls);
        map.put("outputSchema", outputSchema);
        map.put("minimumEvidence", minimumEvidence);
        map.put("queryTerms", queryTerms);
        map.put("evidenceRequirements", evidenceRequirements);
        map.put("evidenceCutoff", evidenceCutoff);
        map.put("evaluationCriteria", evaluationCriteria);
        return map;
    }

    public static ResearchSubtask from(Object value) {
        if (value instanceof ResearchSubtask task) {
            return task;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        String id = text(map.get("id"));
        String section = text(map.get("section"));
        String objective = text(map.get("objective"));
        if (StringUtils.isAnyBlank(id, section, objective)) {
            return null;
        }
        return new ResearchSubtask(
                id,
                section,
                objective,
                DeepResearchState.list(map.get("allowedTools")).stream().map(String::valueOf).toList(),
                integer(map.get("maxToolCalls"), 2),
                StringUtils.defaultIfBlank(text(map.get("outputSchema")), "claims[]"),
                integer(map.get("minimumEvidence"), 1),
                DeepResearchState.list(map.get("queryTerms")).stream().map(String::valueOf).toList(),
                DeepResearchState.list(map.get("evidenceRequirements")).stream().map(String::valueOf).toList(),
                StringUtils.defaultIfBlank(text(map.get("evidenceCutoff")), "截至本次运行开始时可验证的公开资料"),
                StringUtils.defaultIfBlank(text(map.get("evaluationCriteria")), "证据数量、来源可追溯性、冲突与不确定性必须显式输出")
        );
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
