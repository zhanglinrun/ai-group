package com.linrun.agent.eval.judge;

import com.linrun.agent.eval.evaluator.CaseEvaluation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deliberately excludes prompts, tool arguments, trace payloads, secrets, and hidden reasoning. */
public record JudgeRequest(
        String caseId,
        List<String> deterministicFailures,
        String answerExcerpt,
        List<String> citations,
        List<String> successfulTools) {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(bearer\\s+|api[_-]?key[=:]\\s*|authorization[=:]\\s*)[^\\s,;]+" );

    public JudgeRequest {
        caseId = safe(caseId);
        deterministicFailures = List.copyOf(deterministicFailures == null ? List.of() : deterministicFailures);
        answerExcerpt = redact(limit(safe(answerExcerpt), 1_200));
        citations = List.copyOf(citations == null ? List.of() : citations);
        successfulTools = List.copyOf(successfulTools == null ? List.of() : successfulTools);
    }

    public static JudgeRequest from(CaseEvaluation evaluation) {
        return new JudgeRequest(evaluation.caseId(), evaluation.failures(), evaluation.observation().answer(),
                evaluation.observation().citations(), evaluation.observation().successfulTools());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }

    private static String redact(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("<think>") || lower.contains("hidden reasoning")) {
            return "[REDACTED_EVALUATION_OUTPUT]";
        }
        return SECRET.matcher(value).replaceAll("[REDACTED]");
    }
}
