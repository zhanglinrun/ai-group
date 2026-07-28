package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.FileRequest;
import com.linrun.agent.domain.agent.runtime.dto.FileResponse;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeepResearchGraphRunnerTest {

    @Test
    public void shouldRunFourResearchersInParallelAndUploadMarkdownArtifact() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        ParallelBranchExecutor branchExecutor = new ParallelBranchExecutor(true);
        RecordingPrinter printer = new RecordingPrinter();
        AgentRequest request = request("req-deep-parallel");
        AgentContext context = context(request, printer, executor);

        try {
            DeepResearchResult result = new DeepResearchGraphRunner(branchExecutor,
                    (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null)
                    .run(context, request);

            Assert.assertTrue(result.completed());
            Assert.assertEquals("PASSED", result.qualityStatus());
            Assert.assertTrue(result.sourceCount() >= 20);
            Assert.assertTrue(result.markdown().length() >= 15_000);
            Assert.assertFalse(result.artifactRefs().isEmpty());
            Assert.assertTrue(branchExecutor.maxStartedAt() < branchExecutor.minCompletedAt());
            Assert.assertTrue(printer.events.stream().anyMatch(event ->
                    event instanceof AgentStreamEvent.StageOutput output
                            && "deep_research_report".equals(output.outputType())
                            && output.isFinal()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldReturnDegradedReportAfterOneRepairWhenEvidenceIsMissing() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        AgentRequest request = request("req-deep-degraded");
        RecordingPrinter printer = new RecordingPrinter();
        AgentContext context = context(request, printer, executor);

        try {
            DeepResearchResult result = new DeepResearchGraphRunner(new ParallelBranchExecutor(false),
                    (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null)
                    .run(context, request);

            Assert.assertTrue(result.completed());
            Assert.assertEquals("DEGRADED", result.qualityStatus());
            Assert.assertEquals(0, result.sourceCount());
            Assert.assertEquals(1, result.repairCount());
            Assert.assertTrue(result.markdown().contains("证据缺口与修复记录"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldKeepSectionOrderAndResolveCitationsToSources() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        AgentRequest request = request("req-deep-markdown");
        RecordingPrinter printer = new RecordingPrinter();
        AgentContext context = context(request, printer, executor);

        try {
            DeepResearchResult result = new DeepResearchGraphRunner(new ParallelBranchExecutor(true),
                    (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null)
                    .run(context, request);

            ResearchPlan plan = ResearchPlan.create(request.getQuery());
            int previous = -1;
            for (String section : plan.sections()) {
                int index = result.markdown().indexOf("\n## " + section + "\n");
                Assert.assertTrue("missing section: " + section, index > previous);
                previous = index;
            }
            Assert.assertEquals(plan.sections(), finalReportPayload(printer).get("completedSections"));
            Set<String> citations = citationIds(result.markdown());
            for (int i = 1; i <= 20; i++) {
                Assert.assertTrue("missing citation S" + i, citations.contains(String.valueOf(i)));
                Assert.assertTrue(result.markdown().contains("- [S" + i + "] "));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldScopeCheckpointThreadIdByOwnerAndRequest() {
        String ownerOne = DeepResearchGraphRunner.stableThreadId("1001", "req-same");
        String ownerTwo = DeepResearchGraphRunner.stableThreadId("1002", "req-same");

        Assert.assertEquals(ownerOne, DeepResearchGraphRunner.stableThreadId("1001", "req-same"));
        Assert.assertNotEquals(ownerOne, ownerTwo);
    }

    @Test
    public void shouldResumeCompletedCheckpointWithoutRepeatingResearchers() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        AgentRequest request = request("req-deep-resume");
        ParallelBranchExecutor branchExecutor = new ParallelBranchExecutor(true);
        DeepResearchGraphRunner runner = new DeepResearchGraphRunner(branchExecutor, new MemorySaver());

        try {
            DeepResearchResult first = runner.run(context(request, new RecordingPrinter(), executor), request);
            DeepResearchResult second = runner.run(context(request, new RecordingPrinter(), executor), request);

            Assert.assertTrue(first.completed());
            Assert.assertTrue(second.completed());
            Assert.assertEquals(first.markdown(), second.markdown());
            Assert.assertEquals(4, branchExecutor.callCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldStopBeforeResearchersWhenDownstreamAbortsAfterPlanner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        AgentRequest request = request("req-deep-cancelled");
        AbortAfterPlannerPrinter printer = new AbortAfterPlannerPrinter();
        ParallelBranchExecutor branchExecutor = new ParallelBranchExecutor(true);
        AgentContext context = context(request, printer, executor);

        try {
            try {
                new DeepResearchGraphRunner(branchExecutor,
                        (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null)
                        .run(context, request);
                Assert.fail("expected cancellation");
            } catch (RuntimeException expected) {
                Assert.assertTrue(hasCause(expected, CancellationException.class, "DOWNSTREAM_ABORTED"));
            }

            Assert.assertEquals(0, branchExecutor.callCount());
            Assert.assertFalse(printer.events.stream().anyMatch(event ->
                    event instanceof AgentStreamEvent.StageOutput output
                            && "deep_research_report".equals(output.outputType())));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldDegradeAndRepairWhenOneResearchBranchFails() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        AgentRequest request = request("req-deep-branch-failure");
        ParallelBranchExecutor delegate = new ParallelBranchExecutor(true);

        try {
            DeepResearchResult result = new DeepResearchGraphRunner((parentContext, parentRequest, plan, researcherIndex) -> {
                if (researcherIndex == 2) {
                    throw new IllegalStateException("injected branch failure");
                }
                return delegate.execute(parentContext, parentRequest, plan, researcherIndex);
            }, (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null)
                    .run(context(request, new RecordingPrinter(), executor), request);

            Assert.assertTrue(result.completed());
            Assert.assertEquals("DEGRADED", result.qualityStatus());
            Assert.assertTrue(result.sourceCount() > 0 && result.sourceCount() < 20);
            Assert.assertEquals(1, result.repairCount());
            Assert.assertTrue(result.markdown().contains("证据不足"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldPropagateQuotaFailureInsteadOfPublishingDegradedReport() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        AgentRequest request = request("req-deep-quota");
        RecordingPrinter printer = new RecordingPrinter();

        try {
            try {
                new DeepResearchGraphRunner((parentContext, parentRequest, plan, researcherIndex) -> {
                    throw new QuotaInsufficientException("额度不足，无法支持最少256个输出Token");
                }, (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null)
                        .run(context(request, printer, executor), request);
                Assert.fail("expected quota failure");
            } catch (RuntimeException expected) {
                Assert.assertTrue(hasCause(expected, QuotaInsufficientException.class, "额度不足"));
            }

            Assert.assertFalse(printer.events.stream().anyMatch(event ->
                    event instanceof AgentStreamEvent.StageOutput output
                            && "deep_research_report".equals(output.outputType())));
        } finally {
            executor.shutdownNow();
        }
    }

    private AgentRequest request(String requestId) {
        return AgentRequest.builder()
                .requestId(requestId)
                .sessionId("session-deep")
                .ownerId("1001")
                .query("分析企业知识库 Agent 的行业趋势和竞争格局")
                .executionMode("DEEP")
                .outputStyle("markdown")
                .isStream(true)
                .build();
    }

    private AgentContext context(AgentRequest request,
                                 Printer printer,
                                 ExecutorService executor) {
        return AgentContext.builder()
                .requestId(request.getRequestId())
                .sessionId(request.getSessionId())
                .ownerId(Long.valueOf(request.getOwnerId()))
                .query(request.getQuery())
                .printer(printer)
                .runtimeDependencies(ReactorRuntimeDependencies.builder()
                        .reactorConfig(new ReactorConfig())
                        .fileArtifactPort(new StubFileArtifactPort())
                        .taskExecutor(executor)
                        .build())
                .build();
    }

    private static Set<String> citationIds(String markdown) {
        Matcher matcher = Pattern.compile("\\[S(\\d+)]").matcher(markdown);
        Set<String> ids = new LinkedHashSet<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> finalReportPayload(RecordingPrinter printer) {
        return printer.events.stream()
                .filter(event -> event instanceof AgentStreamEvent.StageOutput output
                        && "deep_research_report".equals(output.outputType())
                        && output.isFinal())
                .map(event -> (Map<String, Object>) ((AgentStreamEvent.StageOutput) event).payload())
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type, String text) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current) && String.valueOf(current.getMessage()).contains(text)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static class ParallelBranchExecutor implements ResearchBranchExecutor {
        private final boolean withEvidence;
        private final long[] started = new long[4];
        private final long[] completed = new long[4];
        private final AtomicInteger calls = new AtomicInteger();

        private ParallelBranchExecutor(boolean withEvidence) {
            this.withEvidence = withEvidence;
        }

        @Override
        public ResearchBranchResult execute(AgentContext parentContext,
                                            AgentRequest parentRequest,
                                            ResearchPlan plan,
                                            int researcherIndex) throws Exception {
            calls.incrementAndGet();
            started[researcherIndex - 1] = System.nanoTime();
            Thread.sleep(250);
            completed[researcherIndex - 1] = System.nanoTime();
            List<String> sections = plan.assignedSections(researcherIndex);
            String markdown = sections.stream()
                    .map(section -> "### " + section + "\n\n" + "已完成分支研究，结论严格依赖证据材料。".repeat(40))
                    .reduce("", (left, right) -> left + "\n\n" + right);
            List<ResearchEvidencePacket> evidence = withEvidence
                    ? List.of(new ResearchEvidencePacket("evidence-" + researcherIndex,
                    "来源 " + researcherIndex, "https://example.com/source-" + researcherIndex,
                    "分支证据摘要"))
                    : List.of();
            return new ResearchBranchResult("researcher_" + researcherIndex, sections,
                    markdown, evidence, List.of(), List.of(), started[researcherIndex - 1],
                    completed[researcherIndex - 1]);
        }

        private long maxStartedAt() {
            long max = 0L;
            for (long value : started) {
                max = Math.max(max, value);
            }
            return max;
        }

        private long minCompletedAt() {
            long min = Long.MAX_VALUE;
            for (long value : completed) {
                min = Math.min(min, value);
            }
            return min;
        }

        private int callCount() {
            return calls.get();
        }
    }

    private static class StubFileArtifactPort implements FileArtifactPort {
        @Override
        public FileResponse upload(String serviceBaseUrl, FileRequest request) {
            return FileResponse.builder()
                    .requestId(request.getRequestId())
                    .fileName(request.getFileName())
                    .ossUrl("oss://deep-research/" + request.getFileName())
                    .domainUrl("https://files.example.com/" + request.getFileName())
                    .fileSize(request.getContent().getBytes(StandardCharsets.UTF_8).length)
                    .build();
        }

        @Override
        public FileResponse get(String serviceBaseUrl, FileRequest request) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String readText(String url, Long timeoutSeconds) throws IOException {
            throw new UnsupportedOperationException();
        }
    }

    private static class RecordingPrinter implements Printer {
        protected final List<AgentStreamEvent> events = new ArrayList<>();

        @Override
        public void send(AgentStreamEvent event) {
            events.add(event);
        }

        @Override
        public void close() {
        }
    }

    private static class AbortAfterPlannerPrinter extends RecordingPrinter {
        private volatile boolean aborted;

        @Override
        public void send(AgentStreamEvent event) {
            super.send(event);
            if (event instanceof AgentStreamEvent.StageOutput output
                    && "research_planner".equals(output.toolCallId())) {
                aborted = true;
            }
        }

        @Override
        public boolean isAborted() {
            return aborted;
        }
    }
}
