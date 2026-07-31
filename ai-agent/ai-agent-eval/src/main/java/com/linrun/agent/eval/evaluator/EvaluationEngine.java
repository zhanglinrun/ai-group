package com.linrun.agent.eval.evaluator;

import com.linrun.agent.eval.dataset.EvalCase;
import com.linrun.agent.eval.dataset.EvalDataset;
import com.linrun.agent.eval.judge.JudgeOutcome;
import com.linrun.agent.eval.judge.JudgeRequest;
import com.linrun.agent.eval.judge.LlmJudge;
import com.linrun.agent.eval.runner.EvalCaseRunner;
import com.linrun.agent.eval.runner.EvalRunObservation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EvaluationEngine {
    private final DeterministicEvaluator evaluator;
    private final EvalCaseRunner runner;
    private final LlmJudge judge;
    private final EvaluationThresholds thresholds;

    public EvaluationEngine(DeterministicEvaluator evaluator, EvalCaseRunner runner, LlmJudge judge,
                            EvaluationThresholds thresholds) {
        this.evaluator = evaluator;
        this.runner = runner;
        this.judge = judge;
        this.thresholds = thresholds;
    }

    public EvaluationRun execute(EvalDataset dataset, int trials) {
        List<CaseEvaluation> evaluations = new ArrayList<>();
        List<JudgeOutcome> judgeOutcomes = new ArrayList<>();
        Map<String, EvalCase> casesById = new LinkedHashMap<>();
        for (EvalCase evalCase : dataset.cases()) {
            casesById.put(evalCase.id(), evalCase);
            for (int trial = 1; trial <= trials; trial++) {
                EvalRunObservation observation;
                try {
                    observation = runner.run(evalCase, trial);
                } catch (Exception error) {
                    observation = new EvalRunObservation("", "", "", "", List.of(), List.of(), java.util.Set.of(),
                            false, false, false, false, 0, 0L, 0L,
                            "runner exception: " + error.getClass().getSimpleName());
                }
                CaseEvaluation evaluation = evaluator.evaluate(evalCase, trial, observation);
                evaluations.add(evaluation);
                if (!evaluation.passed()) {
                    judgeOutcomes.add(judge.judge(JudgeRequest.from(evaluation)));
                }
            }
        }
        EvaluationMetrics metrics = metrics(evaluations, casesById);
        GateResult gates = gates(metrics);
        return new EvaluationRun(dataset, evaluations, judgeOutcomes, metrics, gates);
    }

    private EvaluationMetrics metrics(List<CaseEvaluation> evaluations, Map<String, EvalCase> casesById) {
        int passed = 0;
        int schema = 0;
        int permission = 0;
        int recovery = 0;
        int quota = 0;
        int citationsRequired = 0;
        int citationsCovered = 0;
        int parameters = 0;
        int privacy = 0;
        Map<String, Integer> categories = new LinkedHashMap<>();
        for (CaseEvaluation evaluation : evaluations) {
            if (evaluation.passed()) {
                passed++;
            }
            EvalCase evalCase = casesById.get(evaluation.caseId());
            boolean citationFailure = false;
            for (String failure : evaluation.failures()) {
                String category = failure.substring(0, failure.indexOf(':') < 0 ? failure.length() : failure.indexOf(':'))
                        .toLowerCase(Locale.ROOT);
                categories.merge(category, 1, Integer::sum);
                schema += category.equals("schema") || category.equals("protocol") ? 1 : 0;
                permission += category.equals("permission") ? 1 : 0;
                recovery += category.equals("recovery") ? 1 : 0;
                quota += category.equals("quota") ? 1 : 0;
                parameters += category.equals("tool_parameter") ? 1 : 0;
                privacy += category.equals("privacy") || category.equals("trace") ? 1 : 0;
                citationFailure |= category.equals("citation");
            }
            if (evalCase != null && !evalCase.expectedCitations().isEmpty()) {
                citationsRequired++;
                if (!citationFailure) {
                    citationsCovered++;
                }
            }
        }
        return new EvaluationMetrics(evaluations.size(), passed, schema, permission, recovery, quota,
                citationsRequired, citationsCovered, parameters, privacy, categories);
    }

    private GateResult gates(EvaluationMetrics metrics) {
        List<String> violations = new ArrayList<>();
        if (metrics.passedTrials() != metrics.totalTrials()) {
            violations.add(String.format(Locale.ROOT, "deterministic task success is %.1f%%; P120 baseline requires 100%%",
                    metrics.taskSuccessRate() * 100.0d));
        }
        if (metrics.schemaFailures() > 0) {
            violations.add("schema/protocol contracts must be 100% (failures=" + metrics.schemaFailures() + ")");
        }
        if (metrics.permissionFailures() > 0 || metrics.privacyFailures() > 0) {
            violations.add("permission and hidden-reasoning contracts must be 100% (failures="
                    + (metrics.permissionFailures() + metrics.privacyFailures()) + ")");
        }
        if (metrics.recoveryFailures() > 0) {
            violations.add("recovery/resume contracts must be 100% (failures=" + metrics.recoveryFailures() + ")");
        }
        if (metrics.quotaFailures() > 0) {
            violations.add("quota settlement must be exactly once (failures=" + metrics.quotaFailures() + ")");
        }
        if (metrics.toolParameterFailures() > 0) {
            violations.add("tool parameters must match case contracts (failures=" + metrics.toolParameterFailures() + ")");
        }
        if (metrics.citationCoverage() < thresholds.minimumCitationCoverage()) {
            violations.add(String.format(Locale.ROOT, "citation coverage %.1f%% is below %.1f%%",
                    metrics.citationCoverage() * 100.0d, thresholds.minimumCitationCoverage() * 100.0d));
        }
        return new GateResult(violations.isEmpty(), violations);
    }
}
