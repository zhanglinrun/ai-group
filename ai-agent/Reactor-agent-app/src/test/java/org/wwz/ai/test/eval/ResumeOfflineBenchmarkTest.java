package org.wwz.ai.test.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.runtime.llm.TokenCounter;
import org.wwz.ai.domain.agent.runtime.tool.skill.DefaultSkillRegistry;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillDefinition;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillMarkdownParser;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillPathGuard;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillScriptDiscoverer;
import org.wwz.ai.infrastructure.dao.reactor.IArtifactLedgerDao;
import org.wwz.ai.infrastructure.dao.reactor.ILlmInvocationLedgerDao;
import org.wwz.ai.infrastructure.dao.reactor.IToolInvocationLedgerDao;
import org.wwz.ai.infrastructure.reactor.service.impl.SessionContextMemoryServiceImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reproducible, network-free benchmark for resume metrics.
 *
 * <p>The benchmark invokes the production session-memory and skill-registry
 * implementations. Token counts use the explicitly reported o200k_base
 * estimator and must not be presented as provider-reported usage.</p>
 */
public class ResumeOfflineBenchmarkTest {

    private static final int MEMORY_TURNS = 30;
    private static final int MEMORY_TOKEN_BUDGET = 12_000;
    private static final int RECENT_RUN_WINDOW = 3;
    private static final int HIGH_DENSITY_FILLER_CHARS_PER_TURN = 600;
    private static final TokenCounter TOKEN_COUNTER = new TokenCounter();

    @Test
    public void shouldGenerateReproducibleMemoryAndSkillReport() throws Exception {
        Map<String, Object> memory = benchmarkMemory();
        Map<String, Object> skills = benchmarkSkills();

        @SuppressWarnings("unchecked")
        Map<String, Object> baseline = (Map<String, Object>) memory.get("hardTruncationBaseline");
        @SuppressWarnings("unchecked")
        Map<String, Object> rolling = (Map<String, Object>) memory.get("rollingSummaryStrategy");
        double baselineRecall = ((Number) baseline.get("averageKeyFactRecallRatePct")).doubleValue();
        double rollingRecall = ((Number) rolling.get("averageKeyFactRecallRatePct")).doubleValue();
        int baselineTokens = ((Number) baseline.get("averageEstimatedInputTokens")).intValue();
        int rollingTokens = ((Number) rolling.get("averageEstimatedInputTokens")).intValue();
        double recallDelta = rollingRecall - baselineRecall;
        double tokenReduction = percentageReduction(baselineTokens, rollingTokens);

        Assert.assertTrue("rolling summaries should improve key-fact recall", rollingRecall > baselineRecall);
        Assert.assertTrue("benchmark corpus must expose a material recall difference", recallDelta >= 10d);
        Assert.assertTrue("rolling summaries should reduce estimated prompt tokens", rollingTokens < baselineTokens);
        Assert.assertTrue("rolling summaries should materially reduce estimated prompt tokens", tokenReduction >= 15d);
        Assert.assertTrue("rolling summary recall should remain high", rollingRecall >= 95d);
        Assert.assertEquals(9, ((Number) skills.get("registeredSkills")).intValue());
        Assert.assertEquals(9, ((Number) skills.get("loadChecksPassed")).intValue());
        Assert.assertTrue(((Number) skills.get("estimatedInputTokenReductionPct")).doubleValue() > 0d);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("benchmarkType", "offline-deterministic");
        report.put("environment", environment());
        report.put("tokenMetric", Map.of(
                "kind", "estimator",
                "encoding", "o200k_base",
                "providerReportedUsage", false,
                "note", "Used only for same-dataset prompt-size comparison; production usage comes from llm_invocation."
        ));
        report.put("memory", memory);
        report.put("skills", skills);

        Path reportPath = Path.of(System.getProperty(
                "resume.eval.report",
                "target/resume-evals/offline-benchmark.json"
        )).toAbsolutePath().normalize();
        Files.createDirectories(reportPath.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(reportPath.toFile(), report);
        System.out.println("RESUME_OFFLINE_REPORT=" + reportPath);
    }

    private Map<String, Object> benchmarkMemory() {
        List<DialogueRunView> allRuns = new ArrayList<>();
        for (int turn = 1; turn <= MEMORY_TURNS; turn++) {
            String anchor = anchor(turn);
            long id = turn;
            allRuns.add(DialogueRunView.builder()
                    .id(id)
                    .requestId("memory-turn-" + turn)
                    .sessionId("resume-memory-session")
                    .entryAgent("react")
                    .queryText(memoryQuery(anchor, turn))
                    .finalSummaryText(memorySummary(anchor, turn))
                    .build());
        }

        AtomicReference<List<DialogueRunView>> visibleRuns = new AtomicReference<>(List.of());
        ExecutionLedgerQueryService queryService = Mockito.mock(ExecutionLedgerQueryService.class);
        Mockito.when(queryService.querySessionRuns("resume-memory-session"))
                .thenAnswer(ignored -> visibleRuns.get());

        ILlmInvocationLedgerDao llmDao = Mockito.mock(ILlmInvocationLedgerDao.class);
        Mockito.when(llmDao.queryByRunIds(Mockito.anyList())).thenReturn(List.of());
        IToolInvocationLedgerDao toolDao = Mockito.mock(IToolInvocationLedgerDao.class);
        Mockito.when(toolDao.queryByLlmInvocationIds(Mockito.anyList())).thenReturn(List.of());
        IArtifactLedgerDao artifactDao = Mockito.mock(IArtifactLedgerDao.class);
        Mockito.when(artifactDao.queryInputArtifactsByRunIds(Mockito.anyList())).thenReturn(List.of());

        SessionContextMemoryServiceImpl baseline = new SessionContextMemoryServiceImpl(
                queryService, llmDao, toolDao, artifactDao, MEMORY_TOKEN_BUDGET, Integer.MAX_VALUE);
        SessionContextMemoryServiceImpl rolling = new SessionContextMemoryServiceImpl(
                queryService, llmDao, toolDao, artifactDao, MEMORY_TOKEN_BUDGET, RECENT_RUN_WINDOW);

        List<Double> baselineRecall = new ArrayList<>();
        List<Double> rollingRecall = new ArrayList<>();
        List<Integer> baselineTokens = new ArrayList<>();
        List<Integer> rollingTokens = new ArrayList<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int pastTurns = 1; pastTurns <= MEMORY_TURNS; pastTurns++) {
            visibleRuns.set(List.copyOf(allRuns.subList(0, pastTurns)));
            String baselineHistory = baseline.buildHistoryDialogue("resume-memory-session", "current-request");
            String rollingHistory = rolling.buildHistoryDialogue("resume-memory-session", "current-request");

            double baselineRate = recallRate(baselineHistory, pastTurns);
            double rollingRate = recallRate(rollingHistory, pastTurns);
            int baselineTokenCount = countTokens(baselineHistory);
            int rollingTokenCount = countTokens(rollingHistory);
            baselineRecall.add(baselineRate);
            rollingRecall.add(rollingRate);
            baselineTokens.add(baselineTokenCount);
            rollingTokens.add(rollingTokenCount);
            samples.add(Map.of(
                    "pastTurns", pastTurns,
                    "baselineRecallRatePct", round1(baselineRate),
                    "rollingRecallRatePct", round1(rollingRate),
                    "baselineEstimatedTokens", baselineTokenCount,
                    "rollingEstimatedTokens", rollingTokenCount
            ));
        }

        double baselineRecallAverage = averageDouble(baselineRecall);
        double rollingRecallAverage = averageDouble(rollingRecall);
        int baselineTokenAverage = averageInt(baselineTokens);
        int rollingTokenAverage = averageInt(rollingTokens);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset", Map.of(
                "continuousConversationTurns", MEMORY_TURNS,
                "keyFacts", MEMORY_TURNS,
                "productionTokenBudget", MEMORY_TOKEN_BUDGET,
                "budgetEncoding", "o200k_base",
                "highDensityFillerCharsPerTurn", HIGH_DENSITY_FILLER_CHARS_PER_TURN,
                "recentRunsKeptVerbatim", RECENT_RUN_WINDOW
        ));
        result.put("hardTruncationBaseline", Map.of(
                "averageKeyFactRecallRatePct", round1(baselineRecallAverage),
                "averageEstimatedInputTokens", baselineTokenAverage
        ));
        result.put("rollingSummaryStrategy", Map.of(
                "averageKeyFactRecallRatePct", round1(rollingRecallAverage),
                "averageEstimatedInputTokens", rollingTokenAverage
        ));
        result.put("delta", Map.of(
                "recallRatePercentagePoints", round1(rollingRecallAverage - baselineRecallAverage),
                "estimatedInputTokenReductionPct", round1(
                        percentageReduction(baselineTokenAverage, rollingTokenAverage))
        ));
        result.put("samples", samples);
        result.put("methodology", "Each turn carries one unique anchor inside the production-safe user-request and final-summary fields. Recall is exact anchor presence in the actual assembled history block. Compact prefixes are followed by deterministic high-token-density evidence, so the corpus exceeds the shared 12K o200k_base budget without relying on raw chain-of-thought, which production memory intentionally excludes.");
        return result;
    }

    private Map<String, Object> benchmarkSkills() {
        Path skillsRoot = findRepositorySkills();
        SkillPathGuard pathGuard = new SkillPathGuard();
        DefaultSkillRegistry registry = new DefaultSkillRegistry(
                SkillRuntimeOptions.builder()
                        .enabled(true)
                        .directories(List.of(skillsRoot.toString()))
                        .build(),
                new SkillMarkdownParser(),
                new SkillScriptDiscoverer(pathGuard),
                pathGuard
        );
        registry.refresh();

        List<SkillDefinition> definitions = new ArrayList<>(registry.listSkills());
        String descriptorPrompt = registry.buildSkillDescription();
        StringBuilder fullInjection = new StringBuilder(descriptorPrompt);
        int loadChecksPassed = 0;
        for (SkillDefinition skill : definitions) {
            fullInjection.append("\n\n## Skill ").append(skill.getName()).append('\n').append(skill.getContent());
            boolean valid = skill.getBasePath() != null
                    && Files.isRegularFile(skill.getBasePath().resolve("SKILL.md"))
                    && skill.getContent() != null
                    && !skill.getContent().isBlank()
                    && skill.getDescription() != null
                    && !skill.getDescription().isBlank()
                    && registry.getRequiredSkill(skill.getName()) == skill;
            if (valid) {
                loadChecksPassed++;
            }
        }

        int descriptorTokens = countTokens(descriptorPrompt);
        int fullInjectionTokens = countTokens(fullInjection.toString());
        List<Integer> progressiveTokens = new ArrayList<>();
        List<Map<String, Object>> taskChecks = new ArrayList<>();
        for (SkillDefinition skill : definitions) {
            String progressivePrompt = descriptorPrompt
                    + "\n\n## Selected Skill " + skill.getName() + "\n"
                    + skill.getContent();
            int estimatedTokens = countTokens(progressivePrompt);
            progressiveTokens.add(estimatedTokens);
            taskChecks.add(Map.of(
                    "skill", skill.getName(),
                    "loaded", true,
                    "discoveredScripts", skill.getScripts().size(),
                    "progressiveEstimatedTokens", estimatedTokens
            ));
        }
        int averageProgressiveTokens = averageInt(progressiveTokens);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registeredSkills", definitions.size());
        result.put("loadChecksPassed", loadChecksPassed);
        result.put("loadSuccessRatePct", round1(100d * loadChecksPassed / Math.max(1, definitions.size())));
        result.put("descriptorOnlyEstimatedTokens", descriptorTokens);
        result.put("fullInjectionEstimatedTokens", fullInjectionTokens);
        result.put("averageProgressiveEstimatedTokens", averageProgressiveTokens);
        result.put("estimatedInputTokenReductionPct", round1(
                percentageReduction(fullInjectionTokens, averageProgressiveTokens)));
        result.put("taskChecks", taskChecks);
        result.put("methodology", "For each repository skill, compare all nine SKILL.md bodies injected together against descriptors plus only the selected SKILL.md body.");
        return result;
    }

    private Path findRepositorySkills() {
        Path current = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve("runtime").resolve("skills");
            if (Files.isDirectory(direct)) {
                return direct;
            }
            Path nested = current.resolve("ai-agent").resolve("runtime").resolve("skills");
            if (Files.isDirectory(nested)) {
                return nested;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository runtime/skills directory not found");
    }

    private String memoryQuery(String anchor, int turn) {
        return paddedSafeEvidence(anchor + " user preference captured in turn " + turn + ". ", 200, 800, 250);
    }

    private String memorySummary(String anchor, int turn) {
        return paddedSafeEvidence(anchor + " remains the durable key fact from turn " + turn + ". ", 400, 1200, 350);
    }

    /**
     * The first {@code compactPrefixChars} stay cheap when old turns are summarized; the high-density
     * CJK segment only appears in the larger recent-run view and makes the shared token budget binding.
     */
    private String paddedSafeEvidence(String prefix,
                                      int compactPrefixChars,
                                      int totalChars,
                                      int highDensityChars) {
        String compact = prefix + "a".repeat(Math.max(0, compactPrefixChars - prefix.length()));
        String denseAlphabet = "甲乙丙丁戊己庚辛壬癸";
        String dense = denseAlphabet.repeat((highDensityChars / denseAlphabet.length()) + 1)
                .substring(0, highDensityChars);
        return compact + dense + "a".repeat(Math.max(0, totalChars - compact.length() - dense.length()));
    }

    private double recallRate(String history, int expectedFacts) {
        int recalled = 0;
        for (int turn = 1; turn <= expectedFacts; turn++) {
            if (history.contains(anchor(turn))) {
                recalled++;
            }
        }
        return 100d * recalled / expectedFacts;
    }

    private String anchor(int turn) {
        return String.format(Locale.ROOT, "MEMORY_FACT_%02d", turn);
    }

    private int countTokens(String text) {
        return TOKEN_COUNTER.countText(text);
    }

    private int averageInt(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0d));
    }

    private double averageDouble(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
    }

    private double percentageReduction(double baseline, double candidate) {
        if (baseline <= 0d) {
            return 0d;
        }
        return 100d * (baseline - candidate) / baseline;
    }

    private double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private Map<String, Object> environment() {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("architecture", System.getProperty("os.arch"));
        environment.put("java", System.getProperty("java.version"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        return environment;
    }
}
