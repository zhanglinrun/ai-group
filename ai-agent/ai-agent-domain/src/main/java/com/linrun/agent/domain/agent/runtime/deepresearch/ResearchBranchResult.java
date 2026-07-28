package com.linrun.agent.domain.agent.runtime.deepresearch;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ResearchBranchResult(String researcherId,
                                   List<String> assignedSections,
                                   String markdown,
                                   List<ResearchEvidencePacket> evidence,
                                   List<String> conflicts,
                                   List<String> gaps,
                                   long startedAtMillis,
                                   long completedAtMillis) {

    public static ResearchBranchResult failure(int researcherIndex,
                                               List<String> sections,
                                               long startedAtMillis,
                                               Exception error) {
        String message = error == null ? "researcher branch failed" : error.getMessage();
        return new ResearchBranchResult(
                "researcher_" + researcherIndex,
                sections,
                "",
                List.of(),
                List.of(),
                List.of(StringUtils.defaultIfBlank(message, "researcher branch failed")),
                startedAtMillis,
                System.currentTimeMillis()
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("researcherId", researcherId);
        map.put("assignedSections", assignedSections);
        map.put("markdown", markdown);
        map.put("evidence", evidence.stream().map(ResearchEvidencePacket::toMap).toList());
        map.put("conflicts", conflicts);
        map.put("gaps", gaps);
        map.put("startedAtMillis", startedAtMillis);
        map.put("completedAtMillis", completedAtMillis);
        return map;
    }

    public static ResearchBranchResult from(Object value) {
        if (value instanceof ResearchBranchResult result) {
            return result;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return new ResearchBranchResult("", List.of(), "", List.of(), List.of(), List.of(), 0L, 0L);
        }
        return new ResearchBranchResult(
                string(map.get("researcherId")),
                strings(map.get("assignedSections")),
                string(map.get("markdown")),
                DeepResearchState.list(map.get("evidence")).stream().map(ResearchEvidencePacket::from).toList(),
                strings(map.get("conflicts")),
                strings(map.get("gaps")),
                number(map.get("startedAtMillis")),
                number(map.get("completedAtMillis"))
        );
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(string(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static List<String> strings(Object value) {
        return DeepResearchState.list(value).stream().map(String::valueOf).toList();
    }
}
