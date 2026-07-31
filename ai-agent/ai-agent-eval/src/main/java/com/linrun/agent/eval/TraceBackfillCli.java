package com.linrun.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.eval.evaluator.CaseEvaluation;
import com.linrun.agent.eval.evaluator.EvaluationRun;
import com.linrun.agent.eval.report.EvaluationResult;
import com.linrun.agent.eval.report.ReportWriter;
import com.linrun.agent.eval.report.RuntimeFingerprint;
import com.linrun.agent.eval.runner.SaaElasticsearchTraceResolver;
import com.linrun.agent.eval.runner.TraceIdResolver;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Replays a completed evaluation result to backfill asynchronous SAA trace IDs without re-running a model. */
public final class TraceBackfillCli {
    private TraceBackfillCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6 || !"--result".equals(args[0]) || !"--saa-elasticsearch-url".equals(args[2])
                || !"--output".equals(args[4])) {
            System.err.println("Usage: TraceBackfillCli --result RESULT_JSON --saa-elasticsearch-url URL --output DIR");
            System.exit(64);
            return;
        }
        EvaluationResult source = new ObjectMapper().readValue(Path.of(args[1]).toFile(), EvaluationResult.class);
        EvaluationResult backfilled = backfill(source, new SaaElasticsearchTraceResolver(URI.create(args[3]), Duration.ofSeconds(10)));
        new ReportWriter().write(Path.of(args[5]), backfilled.runner() + "-TRACE-BACKFILL", backfilled.trials(),
                backfilled.evaluation(), new RuntimeFingerprint(backfilled.gitCommit(), backfilled.dirtyWorktree(),
                        backfilled.configHash()));
        long resolved = backfilled.evaluation().evaluations().stream()
                .filter(evaluation -> !evaluation.observation().traceId().isBlank()).count();
        System.out.printf("ResearchPilot trace backfill: resolved=%d/%d output=%s%n", resolved,
                backfilled.evaluation().evaluations().size(), Path.of(args[5]).toAbsolutePath().normalize());
    }

    static EvaluationResult backfill(EvaluationResult source, TraceIdResolver resolver) {
        List<CaseEvaluation> evaluations = source.evaluation().evaluations().stream().map(evaluation -> {
            try {
                return new CaseEvaluation(evaluation.caseId(), evaluation.trial(), evaluation.passed(), evaluation.failures(),
                        evaluation.observation().withTraceId(resolver.resolve(evaluation.observation())));
            } catch (Exception ignored) {
                return evaluation;
            }
        }).toList();
        EvaluationRun run = new EvaluationRun(source.evaluation().dataset(), evaluations,
                source.evaluation().judgeOutcomes(), source.evaluation().metrics(), source.evaluation().gates());
        return new EvaluationResult(source.generatedAtUtc(), source.runner(), source.trials(), source.datasetHash(),
                source.configHash(), source.gitCommit(), source.dirtyWorktree(), run);
    }
}
