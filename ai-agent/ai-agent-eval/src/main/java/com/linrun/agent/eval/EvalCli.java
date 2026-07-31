package com.linrun.agent.eval;

import com.linrun.agent.eval.config.EvalConfig;
import com.linrun.agent.eval.dataset.DatasetCatalog;
import com.linrun.agent.eval.dataset.EvalDataset;
import com.linrun.agent.eval.evaluator.DeterministicEvaluator;
import com.linrun.agent.eval.evaluator.EvaluationEngine;
import com.linrun.agent.eval.evaluator.EvaluationRun;
import com.linrun.agent.eval.evaluator.EvaluationThresholds;
import com.linrun.agent.eval.judge.GatewayJsonJudge;
import com.linrun.agent.eval.judge.LlmJudge;
import com.linrun.agent.eval.judge.UnavailableJudge;
import com.linrun.agent.eval.report.ReportWriter;
import com.linrun.agent.eval.report.RuntimeFingerprint;
import com.linrun.agent.eval.runner.EvalCaseRunner;
import com.linrun.agent.eval.runner.GatewaySseRunner;
import com.linrun.agent.eval.runner.OfflineFixtureRunner;
import com.linrun.agent.eval.runner.SaaElasticsearchTraceResolver;
import com.linrun.agent.eval.runner.TraceResolvingRunner;

import java.nio.file.Path;
import java.util.Map;

/** P120 entrypoint. Offline deterministic fixtures are the safe default for PR gating. */
public final class EvalCli {
    private EvalCli() {
    }

    public static void main(String[] args) throws Exception {
        int code = run(args, System.getenv(), Path.of(".").toAbsolutePath().normalize());
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, Map<String, String> environment, Path workspace) throws Exception {
        EvalConfig config;
        try {
            config = EvalConfig.parse(args, environment);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            return 64;
        }
        EvalDataset dataset;
        try {
            dataset = DatasetCatalog.select(DatasetCatalog.loadDefault(), config.caseIds());
        } catch (IllegalArgumentException error) {
            System.err.println("Invalid evaluation case selection: " + error.getMessage());
            return 64;
        }
        EvalCaseRunner runner;
        LlmJudge judge;
        try {
            runner = runner(config, environment);
            judge = judge(config, environment);
        } catch (IllegalArgumentException error) {
            System.err.println("Invalid evaluation runtime configuration: " + error.getMessage());
            return 64;
        }
        EvaluationRun run = new EvaluationEngine(new DeterministicEvaluator(), runner, judge,
                EvaluationThresholds.p120Baseline()).execute(dataset, config.trials());
        Path output = config.outputDirectory().isAbsolute() ? config.outputDirectory()
                : workspace.resolve(config.outputDirectory()).normalize();
        new ReportWriter().write(output, config.runnerMode().name(), config.trials(), run,
                RuntimeFingerprint.capture(workspace, config.configHashMaterial()));
        System.out.printf("ResearchPilot eval: gate=%s trials=%d passed=%d/%d dataset=%s output=%s%n",
                run.gates().passed() ? "PASS" : "FAIL", config.trials(), run.metrics().passedTrials(),
                run.metrics().totalTrials(), dataset.sha256(), output);
        return run.gates().passed() ? 0 : 2;
    }

    private static EvalCaseRunner runner(EvalConfig config, Map<String, String> environment) {
        if (config.runnerMode() == EvalConfig.RunnerMode.OFFLINE) {
            return new OfflineFixtureRunner();
        }
        String token = environment.getOrDefault(config.bearerTokenEnvironment(), "");
        EvalCaseRunner gateway = new GatewaySseRunner(config.gatewayEndpoint(), token, config.timeout());
        return config.saaElasticsearchUrl() == null ? gateway
                : new TraceResolvingRunner(gateway, new SaaElasticsearchTraceResolver(config.saaElasticsearchUrl(),
                java.time.Duration.ofSeconds(10)));
    }

    private static LlmJudge judge(EvalConfig config, Map<String, String> environment) {
        if (config.judgeEndpoint() == null) {
            return new UnavailableJudge("Judge is not configured; failed deterministic cases require human review");
        }
        String token = environment.getOrDefault(config.judgeTokenEnvironment(), "");
        if (token.isBlank()) {
            return new UnavailableJudge("Judge token environment is unavailable; human review is required");
        }
        return new GatewayJsonJudge(config.judgeEndpoint(), token, config.timeout());
    }
}
