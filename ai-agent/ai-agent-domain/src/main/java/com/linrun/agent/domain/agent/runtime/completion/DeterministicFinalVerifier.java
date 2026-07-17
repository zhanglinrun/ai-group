package com.linrun.agent.domain.agent.runtime.completion;

import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Zero-token verifier used until an optional LLM verifier is wired by the app.
 * It checks deterministic structure and coverage; it does not judge factual correctness.
 */
public class DeterministicFinalVerifier implements FinalVerifier {

    @Override
    public CompletionDecision verify(CompletionRequest request) {
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (request == null || StringUtils.isBlank(request.getDraftAnswer())) {
            reasons.add("The final answer draft is empty.");
            actions.add("Produce a concrete final answer for the user goal.");
        }
        TodoList todoList = request == null ? null : request.getTodoList();
        boolean deep = request != null && request.getExecutionProfile() == AgentExecutionProfile.DEEP;
        boolean hasTodo = todoList != null && todoList.getSteps() != null && !todoList.getSteps().isEmpty();
        if (deep && !hasTodo) {
            reasons.add("Deep execution requires an explicit todo list.");
            actions.add("Create a todo list with todo_write before finishing.");
        } else if (hasTodo && (todoList.getStepStatus() == null
                || todoList.getStepStatus().size() != todoList.getSteps().size()
                || todoList.getStepStatus().stream().anyMatch(status -> !"completed".equals(status)))) {
            reasons.add("The todo list still contains unfinished work.");
            actions.add("Complete and individually mark every remaining todo item.");
        }
        verifyComparisonCoverage(request, todoList, reasons, actions);
        if (!reasons.isEmpty()) {
            return CompletionDecision.builder()
                    .canStop(false)
                    .reasons(reasons)
                    .requiredActions(actions)
                    .verifierExecuted(true)
                    .build();
        }
        return CompletionDecision.allow(true);
    }

    private void verifyComparisonCoverage(CompletionRequest request,
                                          TodoList todoList,
                                          List<String> reasons,
                                          List<String> actions) {
        if (request == null) {
            return;
        }
        ComparisonCriteria criteria = extractComparisonCriteria(request.getGoal());
        if (criteria.subjects().size() < 2) {
            return;
        }

        String answer = StringUtils.defaultString(request.getDraftAnswer()).toLowerCase(Locale.ROOT);
        List<String> missingSubjects = criteria.subjects().stream()
                .filter(subject -> !answer.contains(subject.toLowerCase(Locale.ROOT)))
                .toList();
        if (!missingSubjects.isEmpty()) {
            reasons.add("The final comparison is missing requested subjects: "
                    + String.join(", ", missingSubjects));
            actions.add("Compare every requested subject explicitly before finishing.");
        }

        List<String> missingDimensions = new ArrayList<>();
        for (Map.Entry<String, List<String>> dimension : criteria.dimensions().entrySet()) {
            boolean covered = dimension.getValue().stream()
                    .map(alias -> alias.toLowerCase(Locale.ROOT))
                    .anyMatch(answer::contains);
            if (!covered) {
                missingDimensions.add(dimension.getKey());
            }
        }
        if (!missingDimensions.isEmpty()) {
            reasons.add("The final comparison is missing requested dimensions: "
                    + String.join(", ", missingDimensions));
            actions.add("Add the missing comparison dimensions for all requested subjects.");
        }

        List<String> missingPairs = new ArrayList<>();
        for (String subject : criteria.subjects()) {
            for (Map.Entry<String, List<String>> dimension : criteria.dimensions().entrySet()) {
                if (!coversComparisonPair(request.getDraftAnswer(), criteria, subject, dimension.getValue())) {
                    missingPairs.add(subject + "×" + dimension.getKey());
                }
            }
        }
        if (!missingPairs.isEmpty()) {
            reasons.add("The final comparison is missing subject-by-dimension coverage: "
                    + String.join(", ", missingPairs));
            actions.add("Give substantive information for every requested subject and dimension; "
                    + "a global list of names or dimension labels is not sufficient.");
        }

        if (todoList != null && todoList.getSteps() != null) {
            String todoText = String.join("\n", todoList.getSteps()).toLowerCase(Locale.ROOT);
            List<String> unplannedSubjects = criteria.subjects().stream()
                    .filter(subject -> !todoText.contains(subject.toLowerCase(Locale.ROOT)))
                    .toList();
            if (!unplannedSubjects.isEmpty()) {
                reasons.add("The todo plan did not cover requested subjects: "
                        + String.join(", ", unplannedSubjects));
                actions.add("Update the in-progress todo suffix so every requested subject is researched and verified.");
            }

            List<String> unplannedPairs = new ArrayList<>();
            for (String subject : criteria.subjects()) {
                for (Map.Entry<String, List<String>> dimension : criteria.dimensions().entrySet()) {
                    if (!todoMentionsPair(todoList.getSteps(), subject, dimension.getValue())) {
                        unplannedPairs.add(subject + "×" + dimension.getKey());
                    }
                }
            }
            if (!unplannedPairs.isEmpty()) {
                reasons.add("The todo plan did not cover requested subject/dimension pairs: "
                        + String.join(", ", unplannedPairs));
                actions.add("Update only the unfinished todo suffix so every requested comparison cell is covered.");
            }
        }
    }

    private boolean coversComparisonPair(String answer,
                                         ComparisonCriteria criteria,
                                         String subject,
                                         List<String> dimensionAliases) {
        return tableCoversPair(answer, criteria, subject, dimensionAliases)
                || narrativeCoversPair(answer, criteria, subject, dimensionAliases);
    }

    private boolean tableCoversPair(String answer,
                                    ComparisonCriteria criteria,
                                    String subject,
                                    List<String> dimensionAliases) {
        for (List<List<String>> table : parseTables(answer)) {
            for (int headerIndex = 0; headerIndex < table.size(); headerIndex++) {
                List<String> header = table.get(headerIndex);
                int dimensionColumn = findDimensionColumn(header, dimensionAliases);
                int subjectColumn = findSubjectColumn(header, criteria);
                if (dimensionColumn < 0 || subjectColumn < 0) {
                    continue;
                }
                for (int rowIndex = headerIndex + 1; rowIndex < table.size(); rowIndex++) {
                    List<String> row = table.get(rowIndex);
                    if (isTableSeparator(row) || subjectColumn >= row.size()
                            || dimensionColumn >= row.size()
                            || !cellContainsSubject(row.get(subjectColumn), subject)) {
                        continue;
                    }
                    String value = row.get(dimensionColumn);
                    if (!isOnlyRequestedSubject(value, criteria.subjects())
                            && hasSubstantiveValue(value, dimensionAliases)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<List<List<String>>> parseTables(String answer) {
        List<List<List<String>>> tables = new ArrayList<>();
        List<List<String>> current = new ArrayList<>();
        for (String line : StringUtils.defaultString(answer).split("\\R", -1)) {
            if (line.contains("|")) {
                List<String> cells = parseTableRow(line);
                if (cells.size() >= 2) {
                    current.add(cells);
                    continue;
                }
            }
            if (!current.isEmpty()) {
                tables.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            tables.add(current);
        }
        return tables;
    }

    private List<String> parseTableRow(String line) {
        List<String> cells = new ArrayList<>(Arrays.asList(line.split("\\|", -1)));
        if (!cells.isEmpty() && cells.get(0).isBlank()) {
            cells.remove(0);
        }
        if (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
            cells.remove(cells.size() - 1);
        }
        return cells.stream().map(String::trim).toList();
    }

    private int findDimensionColumn(List<String> header, List<String> aliases) {
        for (int index = 0; index < header.size(); index++) {
            String cell = header.get(index).toLowerCase(Locale.ROOT);
            if (aliases.stream()
                    .map(alias -> alias.toLowerCase(Locale.ROOT))
                    .anyMatch(cell::contains)) {
                return index;
            }
        }
        return -1;
    }

    private int findSubjectColumn(List<String> header, ComparisonCriteria criteria) {
        List<String> subjectLabels = List.of("产品", "对象", "名称", "工具", "product", "subject", "name", "tool");
        for (int index = 0; index < header.size(); index++) {
            String cell = header.get(index).toLowerCase(Locale.ROOT);
            if (subjectLabels.stream().anyMatch(cell::contains)) {
                return index;
            }
        }
        for (int index = 0; index < header.size(); index++) {
            String cell = header.get(index).toLowerCase(Locale.ROOT);
            boolean dimensionColumn = criteria.dimensions().values().stream()
                    .flatMap(List::stream)
                    .map(alias -> alias.toLowerCase(Locale.ROOT))
                    .anyMatch(cell::contains);
            if (!dimensionColumn) {
                return index;
            }
        }
        return -1;
    }

    private boolean isTableSeparator(List<String> row) {
        return !row.isEmpty() && row.stream().allMatch(cell -> cell.replaceAll("[:\\-\\s]", "").isEmpty());
    }

    private boolean cellContainsSubject(String cell, String subject) {
        String expected = subject.toLowerCase(Locale.ROOT);
        return StringUtils.defaultString(cell).toLowerCase(Locale.ROOT).contains(expected);
    }

    private boolean isOnlyRequestedSubject(String value, List<String> subjects) {
        String normalized = StringUtils.defaultString(value).toLowerCase(Locale.ROOT)
                .replaceAll("[`*_\\s\\p{P}\\p{S}]", "");
        return subjects.stream()
                .map(subject -> subject.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}\\p{S}]", ""))
                .anyMatch(normalized::equals);
    }

    private boolean narrativeCoversPair(String answer,
                                        ComparisonCriteria criteria,
                                        String subject,
                                        List<String> dimensionAliases) {
        String original = StringUtils.defaultString(answer);
        String lower = original.toLowerCase(Locale.ROOT);
        String normalizedSubject = subject.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < lower.length()) {
            int subjectIndex = lower.indexOf(normalizedSubject, from);
            if (subjectIndex < 0) {
                return false;
            }
            int nextSubject = findNextSubject(lower, criteria.subjects(), subjectIndex + normalizedSubject.length());
            int blockEnd = nextSubject < 0
                    ? Math.min(original.length(), subjectIndex + 800)
                    : Math.min(nextSubject, subjectIndex + 800);
            String block = original.substring(subjectIndex, blockEnd);
            if (dimensionAliases.stream().anyMatch(alias -> hasDimensionDescription(block, criteria, alias))) {
                return true;
            }
            from = subjectIndex + normalizedSubject.length();
        }
        return false;
    }

    private int findNextSubject(String lowerAnswer, List<String> subjects, int fromIndex) {
        int next = -1;
        for (String subject : subjects) {
            int index = lowerAnswer.indexOf(subject.toLowerCase(Locale.ROOT), fromIndex);
            if (index >= 0 && (next < 0 || index < next)) {
                next = index;
            }
        }
        return next;
    }

    private boolean hasDimensionDescription(String block,
                                            ComparisonCriteria criteria,
                                            String dimensionAlias) {
        String lowerBlock = block.toLowerCase(Locale.ROOT);
        String alias = dimensionAlias.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < lowerBlock.length()) {
            int aliasIndex = lowerBlock.indexOf(alias, from);
            if (aliasIndex < 0) {
                return false;
            }
            int valueStart = aliasIndex + alias.length();
            int valueEnd = descriptionBoundary(lowerBlock, criteria, valueStart);
            String value = block.substring(valueStart, valueEnd);
            if (!isOnlyRequestedSubject(value, criteria.subjects())
                    && hasSubstantiveValue(value, List.of())) {
                return true;
            }
            from = valueStart;
        }
        return false;
    }

    private int descriptionBoundary(String lowerBlock, ComparisonCriteria criteria, int fromIndex) {
        int boundary = lowerBlock.length();
        for (String delimiter : List.of("\n", "\r", "；", ";", "。", ".", "！", "!", "？", "?", "|")) {
            int index = lowerBlock.indexOf(delimiter, fromIndex);
            if (index >= 0 && index < boundary) {
                boundary = index;
            }
        }
        for (List<String> aliases : criteria.dimensions().values()) {
            for (String alias : aliases) {
                int index = lowerBlock.indexOf(alias.toLowerCase(Locale.ROOT), fromIndex);
                if (index >= 0 && index < boundary) {
                    boundary = index;
                }
            }
        }
        return boundary;
    }

    private boolean hasSubstantiveValue(String value, List<String> aliases) {
        String normalized = StringUtils.defaultString(value)
                .replace("`", "")
                .replace("*", "")
                .replace("_", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        Set<String> placeholders = new HashSet<>(List.of(
                "-", "—", "n/a", "na", "none", "null", "unknown", "未知", "暂无",
                "待补充", "未提供", "tbd", "not available", "不同", "相同", "较好", "一般", "如下", "见上"
        ));
        String compact = normalized.replaceAll("[\\s:：,，、/\\-—]", "");
        if (placeholders.contains(normalized) || placeholders.contains(compact)) {
            return false;
        }
        for (String alias : aliases) {
            String normalizedAlias = alias.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
            if (compact.equals(normalizedAlias)) {
                return false;
            }
            compact = compact.replace(normalizedAlias, "");
        }
        if (normalized.chars().anyMatch(Character::isDigit)) {
            return true;
        }
        String semantic = compact.replaceAll("[\\p{P}\\p{S}]", "");
        return semantic.length() >= 2;
    }

    private boolean todoMentionsPair(List<String> steps,
                                     String subject,
                                     List<String> dimensionAliases) {
        String normalizedSubject = subject.toLowerCase(Locale.ROOT);
        return steps.stream()
                .map(step -> StringUtils.defaultString(step).toLowerCase(Locale.ROOT))
                .anyMatch(step -> step.contains(normalizedSubject)
                        && dimensionAliases.stream()
                        .map(alias -> alias.toLowerCase(Locale.ROOT))
                        .anyMatch(step::contains));
    }

    private ComparisonCriteria extractComparisonCriteria(String goal) {
        String normalized = StringUtils.defaultString(goal).trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        int markerEnd = comparisonMarkerEnd(lower);
        if (markerEnd < 0) {
            return ComparisonCriteria.empty();
        }

        Map<String, List<String>> requestedDimensions = new LinkedHashMap<>();
        registerDimension(lower, requestedDimensions, "price",
                List.of("价格", "定价", "费用", "成本", "price", "pricing", "cost"));
        registerDimension(lower, requestedDimensions, "capability",
                List.of("能力", "功能", "特性", "capability", "capabilities", "feature", "features"));
        registerDimension(lower, requestedDimensions, "difference",
                List.of("区别", "不同", "差异", "difference", "differences"));
        if (requestedDimensions.isEmpty()) {
            return ComparisonCriteria.empty();
        }

        int subjectEnd = normalized.length();
        for (List<String> aliases : requestedDimensions.values()) {
            for (String alias : aliases) {
                int index = lower.indexOf(alias.toLowerCase(Locale.ROOT), markerEnd);
                if (index >= markerEnd && index < subjectEnd) {
                    subjectEnd = index;
                }
            }
        }
        String subjectSegment = normalized.substring(markerEnd, subjectEnd)
                .replaceFirst("^(?:下|一下|看看|这几款|以下|三款|几款)\\s*", "")
                .replaceFirst("(?:之间|有?什么|怎么样|如何|的|在|关于)\\s*$", "")
                .trim();
        if (subjectSegment.isEmpty()) {
            return ComparisonCriteria.empty();
        }
        List<String> subjects = List.of(subjectSegment.split("\\s*(?:、|，|,|/|\\bvs\\.?\\b|和)\\s*"))
                .stream()
                .map(String::trim)
                .filter(subject -> subject.length() >= 2)
                .distinct()
                .toList();
        return new ComparisonCriteria(subjects, requestedDimensions);
    }

    private int comparisonMarkerEnd(String lowerGoal) {
        for (String marker : List.of("对比", "比较", "compare")) {
            int index = lowerGoal.indexOf(marker);
            if (index >= 0) {
                return index + marker.length();
            }
        }
        return -1;
    }

    private void registerDimension(String lowerGoal,
                                   Map<String, List<String>> target,
                                   String name,
                                   List<String> aliases) {
        if (aliases.stream().map(alias -> alias.toLowerCase(Locale.ROOT)).anyMatch(lowerGoal::contains)) {
            target.put(name, aliases);
        }
    }

    private record ComparisonCriteria(List<String> subjects, Map<String, List<String>> dimensions) {
        private static ComparisonCriteria empty() {
            return new ComparisonCriteria(List.of(), Map.of());
        }
    }
}
