package com.linrun.agent.domain.agent.runtime.deepresearch;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Deep Research plan is derived from the user's question and retains a
 * bounded contract for every parallel researcher.
 */
public record ResearchPlan(String title,
                           List<String> sections,
                           List<String> subQuestions,
                           Map<Integer, List<String>> sectionAssignments,
                           List<ResearchSubtask> subtasks) {

    private static final List<String> RESEARCH_ANGLES = List.of("事实边界", "证据机制", "比较与冲突", "风险与行动");
    private static final List<String> DEFAULT_ALLOWED_TOOLS = List.of("search_web", "fetch_page", "extract_evidence", "analyze_file");
    private static final String OUTPUT_SCHEMA = "{claims:[{claimId,evidenceId,title,url,excerpt,confidence}],gaps:[string],conflicts:[string]}";
    private static final List<String> DELIVERY_DIRECTIVE_MARKERS = List.of(
            "调用", "生成", "输出", "交付", "report_tool", "filetype", "markdown", "pptx", "artifact",
            "generate", "output", "deliver");
    private static final List<String> EVIDENCE_DIRECTIVE_MARKERS = List.of(
            "url", "链接", "引用", "来源", "source", "citation");

    public static ResearchPlan create(String query) {
        String normalizedQuery = StringUtils.defaultIfBlank(query, "待研究主题").trim();
        List<String> questionParts = splitQuestion(normalizedQuery);
        int taskCount = Math.min(4, Math.max(2, questionParts.size()));
        List<ResearchSubtask> tasks = new ArrayList<>(taskCount);
        Map<Integer, List<String>> assignments = new LinkedHashMap<>();
        for (int index = 0; index < taskCount; index++) {
            String focus = questionParts.get(index % questionParts.size());
            String angle = RESEARCH_ANGLES.get(index);
            String section = focus + "：" + angle;
            String id = "subtask-" + (index + 1);
            tasks.add(new ResearchSubtask(
                    id,
                    section,
                    "围绕“" + focus + "”验证" + angle + "，只输出可由公开材料支持或明确缺失的判断。",
                    DEFAULT_ALLOWED_TOOLS,
                    3,
                    OUTPUT_SCHEMA,
                    1,
                    List.of(focus, angle),
                    List.of("至少一条抓取后可访问的 HTTP(S) 来源", "每个 claim 必须有 evidenceId、title、URL、原文摘录和 claimId"),
                    "截至本次运行开始时可验证的公开资料",
                    "最少证据、引用覆盖率、时效性、冲突与证据缺口均需通过 Reviewer 校验"
            ));
            assignments.put(index + 1, List.of(section));
        }
        return fromParts("深度调研：" + normalizedQuery, assignments, tasks);
    }

    public List<String> assignedSections(int researcherIndex) {
        return sectionAssignments.getOrDefault(researcherIndex, List.of());
    }

    public List<ResearchSubtask> assignedSubtasks(int researcherIndex) {
        List<String> assigned = assignedSections(researcherIndex);
        return subtasks.stream().filter(task -> assigned.contains(task.section())).toList();
    }

    public List<Integer> researcherIndexes() {
        return sectionAssignments.keySet().stream().filter(index -> !assignedSections(index).isEmpty()).toList();
    }

    /** Returns a plan restricted to the Reviewer-selected subtask IDs. */
    public ResearchPlan revision(List<String> targetSubtaskIds) {
        if (targetSubtaskIds == null || targetSubtaskIds.isEmpty()) {
            return this;
        }
        List<ResearchSubtask> selected = subtasks.stream()
                .filter(task -> targetSubtaskIds.contains(task.id()))
                .toList();
        if (selected.isEmpty()) {
            return this;
        }
        Map<Integer, List<String>> assignments = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String>> entry : sectionAssignments.entrySet()) {
            List<String> selectedSections = entry.getValue().stream()
                    .filter(section -> selected.stream().anyMatch(task -> task.section().equals(section)))
                    .toList();
            if (!selectedSections.isEmpty()) {
                assignments.put(entry.getKey(), selectedSections);
            }
        }
        return fromParts(title, assignments, selected);
    }

    public List<String> subtaskIdsForSections(List<String> targetSections) {
        if (targetSections == null || targetSections.isEmpty()) {
            return List.of();
        }
        return subtasks.stream()
                .filter(task -> targetSections.contains(task.section()))
                .map(ResearchSubtask::id)
                .toList();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("sections", sections);
        map.put("subQuestions", subQuestions);
        Map<String, Object> assignments = new LinkedHashMap<>();
        sectionAssignments.forEach((key, value) -> assignments.put(String.valueOf(key), value));
        map.put("sectionAssignments", assignments);
        map.put("subtasks", subtasks.stream().map(ResearchSubtask::toMap).toList());
        return map;
    }

    public static ResearchPlan from(Object value, String fallbackQuery) {
        if (value instanceof ResearchPlan plan) {
            return plan;
        }
        if (!(value instanceof Map<?, ?> source)) {
            return create(fallbackQuery);
        }
        String title = text(source.get("title"));
        Map<Integer, List<String>> assignments = assignments(source.get("sectionAssignments"));
        List<ResearchSubtask> tasks = DeepResearchState.list(source.get("subtasks")).stream()
                .map(ResearchSubtask::from)
                .filter(Objects::nonNull)
                .toList();
        if (tasks.isEmpty() || assignments.isEmpty()) {
            return create(StringUtils.defaultIfBlank(fallbackQuery, title));
        }
        return fromParts(StringUtils.defaultIfBlank(title, "深度调研"), assignments, tasks);
    }

    private static ResearchPlan fromParts(String title,
                                          Map<Integer, List<String>> assignments,
                                          List<ResearchSubtask> tasks) {
        List<String> sections = tasks.stream().map(ResearchSubtask::section).toList();
        List<String> questions = tasks.stream().map(ResearchSubtask::objective).toList();
        return new ResearchPlan(title, sections, questions, Map.copyOf(assignments), List.copyOf(tasks));
    }

    private static Map<Integer, List<String>> assignments(Object value) {
        Map<Integer, List<String>> assignments = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> rawMap) {
            rawMap.forEach((key, assigned) -> {
                int index = integer(key);
                if (index > 0) {
                    assignments.put(index, strings(assigned));
                }
            });
        }
        return assignments;
    }

    private static List<String> splitQuestion(String query) {
        List<String> parts = Arrays.stream(query.split("[、，,。！？；;：:]"))
                .map(String::trim)
                .filter(part -> part.length() >= 2)
                .filter(ResearchPlan::isResearchQuestion)
                .distinct()
                .toList();
        return parts.isEmpty() ? List.of(query) : parts;
    }

    /**
     * Delivery and citation-format clauses constrain the final artifact, but
     * are not independent research questions for a branch to search.
     */
    private static boolean isResearchQuestion(String part) {
        String normalized = StringUtils.lowerCase(part);
        if (StringUtils.containsAny(normalized, DELIVERY_DIRECTIVE_MARKERS.toArray(String[]::new))) {
            return false;
        }
        return !(StringUtils.containsAny(normalized, "给出", "提供", "附上")
                && StringUtils.containsAny(normalized, EVIDENCE_DIRECTIVE_MARKERS.toArray(String[]::new)));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
