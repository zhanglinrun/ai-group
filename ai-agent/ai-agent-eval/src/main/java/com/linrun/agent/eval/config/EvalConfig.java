package com.linrun.agent.eval.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable CLI configuration. Tokens are read only from an environment variable and are never serialized. */
public record EvalConfig(
        RunnerMode runnerMode,
        URI gatewayEndpoint,
        String bearerTokenEnvironment,
        URI judgeEndpoint,
        String judgeTokenEnvironment,
        URI saaElasticsearchUrl,
        int trials,
        Set<String> caseIds,
        Path outputDirectory,
        Duration timeout) {
    public enum RunnerMode { OFFLINE, GATEWAY }

    public EvalConfig {
        trials = Math.max(1, trials);
        gatewayEndpoint = gatewayEndpoint == null
                ? URI.create("http://127.0.0.1:8080/web/api/v1/gpt/queryAgentStreamIncr") : gatewayEndpoint;
        bearerTokenEnvironment = blankOr(bearerTokenEnvironment, "RESEARCHPILOT_EVAL_BEARER_TOKEN");
        judgeTokenEnvironment = blankOr(judgeTokenEnvironment, bearerTokenEnvironment);
        caseIds = Set.copyOf(caseIds == null ? Set.of() : caseIds);
        outputDirectory = outputDirectory == null ? Path.of("docs/evals/reports/p120-offline") : outputDirectory;
        timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
    }

    public static EvalConfig offline(Path outputDirectory, int trials) {
        return new EvalConfig(RunnerMode.OFFLINE, null, null, null, null, null, trials, Set.of(), outputDirectory, Duration.ofMinutes(1));
    }

    public static EvalConfig parse(String[] args, Map<String, String> environment) {
        Map<String, String> options = options(args);
        if (options.containsKey("help")) {
            throw new IllegalArgumentException(help());
        }
        String gateway = options.getOrDefault("gateway-url", environment.get("RESEARCHPILOT_EVAL_GATEWAY_URL"));
        RunnerMode mode = gateway == null || gateway.isBlank() ? RunnerMode.OFFLINE : RunnerMode.GATEWAY;
        String output = options.getOrDefault("output", environment.getOrDefault(
                "RESEARCHPILOT_EVAL_OUTPUT", "docs/evals/reports/p120-offline"));
        String judge = options.getOrDefault("judge-url", environment.get("RESEARCHPILOT_EVAL_JUDGE_URL"));
        return new EvalConfig(mode,
                gateway == null || gateway.isBlank() ? null : URI.create(gateway),
                options.getOrDefault("token-env", environment.getOrDefault(
                        "RESEARCHPILOT_EVAL_TOKEN_ENV", "RESEARCHPILOT_EVAL_BEARER_TOKEN")),
                judge == null || judge.isBlank() ? null : URI.create(judge),
                options.getOrDefault("judge-token-env", environment.getOrDefault(
                        "RESEARCHPILOT_EVAL_JUDGE_TOKEN_ENV", "RESEARCHPILOT_EVAL_BEARER_TOKEN")),
                uri(options.getOrDefault("saa-elasticsearch-url", environment.get("RESEARCHPILOT_EVAL_SAA_ELASTICSEARCH_URL"))),
                parsePositive(options.getOrDefault("trials", environment.getOrDefault("RESEARCHPILOT_EVAL_TRIALS", "1"))),
                csv(options.getOrDefault("case-ids", environment.getOrDefault("RESEARCHPILOT_EVAL_CASE_IDS", ""))),
                Path.of(output), Duration.ofSeconds(parsePositive(options.getOrDefault("timeout-seconds", "600"))));
    }

    public String configHashMaterial() {
        return String.join("\n", runnerMode.name(), gatewayEndpoint.toString(), bearerTokenEnvironment,
                judgeEndpoint == null ? "" : judgeEndpoint.toString(), judgeTokenEnvironment,
                saaElasticsearchUrl == null ? "" : saaElasticsearchUrl.toString(),
                Integer.toString(trials), String.join(",", caseIds.stream().sorted().toList()),
                outputDirectory.normalize().toString(), Long.toString(timeout.toSeconds()));
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + argument);
            }
            String name = argument.substring(2);
            if (name.equals("help")) {
                result.put(name, "true");
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for --" + name);
            }
            result.put(name, args[++i]);
        }
        return result;
    }

    private static int parsePositive(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException("value must be positive: " + value);
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("value must be an integer: " + value, error);
        }
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static URI uri(String value) {
        return value == null || value.isBlank() ? null : URI.create(value);
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static String help() {
        return "Usage: EvalCli [--gateway-url URL --token-env ENV] [--trials N] [--output DIR] "
                + "[--case-ids id1,id2] [--saa-elasticsearch-url URL] [--judge-url URL --judge-token-env ENV] [--timeout-seconds N]. "
                + "Without --gateway-url it runs deterministic offline fixtures.";
    }
}
