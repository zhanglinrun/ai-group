package com.linrun.agent.domain.agent.runtime.deepresearch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reviewer-directed, bounded retry plan for only the affected subtasks. */
public record ResearchPlanRevision(List<String> targetSubtaskIds,
                                   List<String> reasons) {

    public static ResearchPlanRevision none() {
        return new ResearchPlanRevision(List.of(), List.of());
    }

    public boolean hasTargets() {
        return !targetSubtaskIds.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("targetSubtaskIds", targetSubtaskIds);
        map.put("reasons", reasons);
        return map;
    }

    public static ResearchPlanRevision from(Object value) {
        if (value instanceof ResearchPlanRevision revision) {
            return revision;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return none();
        }
        return new ResearchPlanRevision(
                DeepResearchState.list(map.get("targetSubtaskIds")).stream().map(String::valueOf).toList(),
                DeepResearchState.list(map.get("reasons")).stream().map(String::valueOf).toList());
    }
}
