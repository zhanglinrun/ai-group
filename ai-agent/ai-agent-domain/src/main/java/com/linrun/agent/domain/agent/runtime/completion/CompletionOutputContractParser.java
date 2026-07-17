package com.linrun.agent.domain.agent.runtime.completion;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservatively extracts explicitly requested snake_case output fields from a user goal.
 */
public final class CompletionOutputContractParser {

    private static final int MAX_OUTPUT_CLAUSE_CHARS = 512;
    private static final Pattern OUTPUT_CUE = Pattern.compile(
            "(?iu)(?:列出|输出|返回|"
                    + "(?<![a-zA-Z0-9_])(?:list|output|return)(?![a-zA-Z0-9_]))"
                    + "\\s*(?:以下|下列|the\\s+following|these)?\\s*[:：]?");
    private static final Pattern SNAKE_CASE_FIELD =
            Pattern.compile("(?<![a-zA-Z0-9_])([a-z][a-z0-9]*(?:_[a-z0-9]+)+)(?![a-zA-Z0-9_])");
    private static final Pattern CLAUSE_END = Pattern.compile("[。！？;；\\r\\n]");

    public CompletionOutputContract parse(String goal) {
        if (StringUtils.isBlank(goal)) {
            return CompletionOutputContract.none();
        }

        LinkedHashSet<String> requiredFields = new LinkedHashSet<>();
        Matcher cueMatcher = OUTPUT_CUE.matcher(goal);
        while (cueMatcher.find()) {
            if (isNegated(goal, cueMatcher.start())) {
                continue;
            }
            int segmentEnd = findSegmentEnd(goal, cueMatcher.end());
            List<String> fields = extractFields(goal.substring(cueMatcher.end(), segmentEnd));
            if (fields.size() >= 2) {
                requiredFields.addAll(fields);
            }
        }
        return requiredFields.size() >= 2
                ? CompletionOutputContract.of(requiredFields)
                : CompletionOutputContract.none();
    }

    private int findSegmentEnd(String goal, int fromIndex) {
        int boundedEnd = Math.min(goal.length(), fromIndex + MAX_OUTPUT_CLAUSE_CHARS);
        Matcher endMatcher = CLAUSE_END.matcher(goal);
        endMatcher.region(fromIndex, boundedEnd);
        return endMatcher.find() ? endMatcher.start() : boundedEnd;
    }

    private List<String> extractFields(String clause) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        Matcher fieldMatcher = SNAKE_CASE_FIELD.matcher(clause);
        while (fieldMatcher.find()) {
            fields.add(fieldMatcher.group(1));
        }
        return new ArrayList<>(fields);
    }

    private boolean isNegated(String goal, int cueStart) {
        int prefixStart = Math.max(0, cueStart - 12);
        String prefix = goal.substring(prefixStart, cueStart)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return prefix.matches(".*(?:不要|无需|不必|不需要|不得|禁止|避免).{0,8}$")
                || prefix.matches(".*(?:do not|don't|must not|never).{0,8}$");
    }
}
