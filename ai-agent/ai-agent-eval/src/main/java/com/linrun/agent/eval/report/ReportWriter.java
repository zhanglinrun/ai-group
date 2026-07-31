package com.linrun.agent.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linrun.agent.eval.dataset.EvalCase;
import com.linrun.agent.eval.evaluator.CaseEvaluation;
import com.linrun.agent.eval.evaluator.EvaluationRun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Emits reviewable JSON, Markdown, HTML, and a self-contained failed-case regression set. */
public final class ReportWriter {
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public EvaluationResult write(Path directory, String runnerName, int trials, EvaluationRun run,
                                  RuntimeFingerprint fingerprint) throws IOException {
        Files.createDirectories(directory);
        EvaluationResult result = new EvaluationResult(Instant.now().toString(), runnerName, trials,
                run.dataset().sha256(), fingerprint.configHash(), fingerprint.gitCommit(), fingerprint.dirtyWorktree(), run);
        Files.writeString(directory.resolve("result.json"), json.writeValueAsString(result) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("report.md"), markdown(result), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("report.html"), html(result), StandardCharsets.UTF_8);
        writeRegressionSet(directory.resolve("regression-set.jsonl"), run);
        return result;
    }

    private void writeRegressionSet(Path output, EvaluationRun run) throws IOException {
        Map<String, EvalCase> cases = new LinkedHashMap<>();
        run.dataset().cases().forEach(evalCase -> cases.put(evalCase.id(), evalCase));
        StringBuilder content = new StringBuilder();
        for (CaseEvaluation evaluation : run.evaluations()) {
            if (!evaluation.passed()) {
                EvalCase evalCase = cases.get(evaluation.caseId());
                if (evalCase != null) {
                    content.append(json.writeValueAsString(Map.of(
                            "datasetHash", run.dataset().sha256(),
                            "trial", evaluation.trial(),
                            "failures", evaluation.failures(),
                            "case", evalCase))).append(System.lineSeparator());
                }
            }
        }
        Files.writeString(output, content.toString(), StandardCharsets.UTF_8);
    }

    private static String markdown(EvaluationResult result) {
        var metrics = result.evaluation().metrics();
        var gates = result.evaluation().gates();
        StringBuilder report = new StringBuilder("# ResearchPilot evaluation report\n\n");
        report.append("- Generated (UTC): `").append(result.generatedAtUtc()).append("`\n")
                .append("- Runner: `").append(result.runner()).append("`\n")
                .append("- Dataset hash: `").append(result.datasetHash()).append("`\n")
                .append("- Config hash: `").append(result.configHash()).append("`\n")
                .append("- Git commit: `").append(result.gitCommit()).append("`\n")
                .append("- Dirty worktree: `").append(result.dirtyWorktree()).append("`\n")
                .append("- Gate: **").append(gates.passed() ? "PASS" : "FAIL").append("**\n\n")
                .append("## Metrics\n\n")
                .append("| Metric | Value |\n| --- | ---: |\n")
                .append("| Trials | ").append(metrics.totalTrials()).append(" |\n")
                .append("| Passed | ").append(metrics.passedTrials()).append(" |\n")
                .append("| Task success | ").append(percent(metrics.taskSuccessRate())).append(" |\n")
                .append("| Citation coverage | ").append(percent(metrics.citationCoverage())).append(" |\n")
                .append("| Schema/protocol failures | ").append(metrics.schemaFailures()).append(" |\n")
                .append("| Permission/privacy failures | ").append(metrics.permissionFailures() + metrics.privacyFailures()).append(" |\n")
                .append("| Recovery failures | ").append(metrics.recoveryFailures()).append(" |\n")
                .append("| Quota failures | ").append(metrics.quotaFailures()).append(" |\n")
                .append("| Tool-parameter failures | ").append(metrics.toolParameterFailures()).append(" |\n\n");
        if (!gates.violations().isEmpty()) {
            report.append("## Gate violations\n\n");
            gates.violations().forEach(violation -> report.append("- ").append(violation).append("\n"));
            report.append('\n');
        }
        report.append("## Case results\n\n| Case | Trial | Result | Failures |\n| --- | ---: | --- | --- |\n");
        result.evaluation().evaluations().forEach(evaluation -> report.append("| `").append(evaluation.caseId())
                .append("` | ").append(evaluation.trial()).append(" | ")
                .append(evaluation.passed() ? "PASS" : "FAIL").append(" | ")
                .append(evaluation.failures().isEmpty() ? "" : escapeMarkdown(String.join("; ", evaluation.failures())))
                .append(" |\n"));
        long unavailable = result.evaluation().judgeOutcomes().stream()
                .filter(outcome -> outcome.status().name().equals("UNAVAILABLE")).count();
        report.append("\nJudge requests: ").append(result.evaluation().judgeOutcomes().size())
                .append("; unavailable/manual-review fallback: ").append(unavailable).append(".\n");
        report.append("\nFailed cases (if any) are emitted to `regression-set.jsonl`. Judge output is advisory and cannot pass a deterministic failure.\n");
        return report.toString();
    }

    private static String html(EvaluationResult result) {
        String markdown = markdown(result);
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>ResearchPilot evaluation</title>"
                + "<style>body{font-family:system-ui,sans-serif;margin:2rem;line-height:1.45}pre{white-space:pre-wrap}"
                + ".pass{color:#157347}.fail{color:#b02a37}</style></head><body><pre>"
                + escapeHtml(markdown) + "</pre></body></html>";
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0d);
    }

    private static String escapeMarkdown(String value) {
        return value.replace("|", "\\|");
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
