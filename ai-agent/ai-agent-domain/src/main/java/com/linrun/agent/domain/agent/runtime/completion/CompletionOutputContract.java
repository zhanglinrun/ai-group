package com.linrun.agent.domain.agent.runtime.completion;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal typed contract for field names that must appear in the final answer.
 * It intentionally stores field names only, never tool output values.
 */
public record CompletionOutputContract(List<String> requiredFields) {

    private static final Pattern SNAKE_CASE_FIELD =
            Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)+");

    public CompletionOutputContract {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (requiredFields != null) {
            requiredFields.stream()
                    .filter(StringUtils::isNotBlank)
                    .map(String::trim)
                    .filter(field -> SNAKE_CASE_FIELD.matcher(field).matches())
                    .forEach(normalized::add);
        }
        requiredFields = List.copyOf(normalized);
    }

    public static CompletionOutputContract of(Collection<String> requiredFields) {
        return new CompletionOutputContract(requiredFields == null
                ? List.of()
                : new ArrayList<>(requiredFields));
    }

    public static CompletionOutputContract none() {
        return new CompletionOutputContract(List.of());
    }

    public List<String> missingFrom(String finalAnswer) {
        String answer = StringUtils.defaultString(finalAnswer);
        return requiredFields.stream()
                .filter(field -> !containsExactField(answer, field))
                .toList();
    }

    public boolean isEmpty() {
        return requiredFields.isEmpty();
    }

    private boolean containsExactField(String answer, String field) {
        Pattern exactField = Pattern.compile(
                "(?<![a-zA-Z0-9_])" + Pattern.quote(field) + "(?![a-zA-Z0-9_])");
        return exactField.matcher(answer).find();
    }
}
