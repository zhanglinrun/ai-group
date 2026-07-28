package com.linrun.agent.domain.agent.runtime.deepresearch;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ResearchPlan(String title,
                           List<String> sections,
                           List<String> subQuestions,
                           Map<Integer, List<String>> sectionAssignments) {

    private static final List<String> DEFAULT_SECTIONS = List.of(
            "研究边界与核心问题",
            "背景脉络与当前状态",
            "市场环境与需求变化",
            "关键参与者与竞争格局",
            "用户场景与业务影响",
            "技术路径与方案选项",
            "数据证据与案例材料",
            "风险约束与不确定性",
            "未来趋势与演化方向",
            "结论与行动建议"
    );

    public static ResearchPlan create(String query) {
        String normalizedQuery = StringUtils.defaultIfBlank(query, "待研究主题").trim();
        List<String> sections = new ArrayList<>(DEFAULT_SECTIONS);
        List<String> subQuestions = sections.stream()
                .map(section -> section + "：" + normalizedQuery)
                .toList();
        Map<Integer, List<String>> assignments = new LinkedHashMap<>();
        for (int researcher = 1; researcher <= 4; researcher++) {
            assignments.put(researcher, new ArrayList<>());
        }
        for (int i = 0; i < sections.size(); i++) {
            assignments.get((i % 4) + 1).add(sections.get(i));
        }
        return new ResearchPlan("熊博士深度调研：" + normalizedQuery, sections, subQuestions, assignments);
    }

    public List<String> assignedSections(int researcherIndex) {
        return sectionAssignments.getOrDefault(researcherIndex, List.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("sections", sections);
        map.put("subQuestions", subQuestions);
        Map<String, Object> assignments = new LinkedHashMap<>();
        sectionAssignments.forEach((key, value) -> assignments.put(String.valueOf(key), value));
        map.put("sectionAssignments", assignments);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static ResearchPlan from(Object value, String fallbackQuery) {
        if (value instanceof ResearchPlan plan) {
            return plan;
        }
        if (!(value instanceof Map<?, ?> source)) {
            return create(fallbackQuery);
        }
        String title = string(source.get("title"));
        List<String> sections = strings(source.get("sections"));
        List<String> subQuestions = strings(source.get("subQuestions"));
        Map<Integer, List<String>> assignments = new LinkedHashMap<>();
        Object rawAssignments = source.get("sectionAssignments");
        if (rawAssignments instanceof Map<?, ?> rawMap) {
            rawMap.forEach((key, assigned) -> assignments.put(integer(key), strings(assigned)));
        }
        if (sections.isEmpty()) {
            sections = DEFAULT_SECTIONS;
        }
        if (subQuestions.isEmpty()) {
            subQuestions = sections;
        }
        if (assignments.isEmpty()) {
            return create(StringUtils.defaultIfBlank(fallbackQuery, title));
        }
        return new ResearchPlan(StringUtils.defaultIfBlank(title, "熊博士深度调研"), sections, subQuestions, assignments);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(string(value));
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
