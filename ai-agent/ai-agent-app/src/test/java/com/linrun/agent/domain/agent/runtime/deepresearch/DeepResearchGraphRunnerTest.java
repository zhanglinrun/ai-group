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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DeepResearchGraphRunnerTest {

    @Test
    public void shouldRunQuestionDrivenSubtasksWithOnlyRealCitations() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        EvidenceBranchExecutor branchExecutor = new EvidenceBranchExecutor(false, true);
        AgentRequest request = request("req-deep-real", "比较市场规模、竞争者策略、监管风险、用户 adoption");
        RecordingPrinter printer = new RecordingPrinter();
        try {
            DeepResearchResult result = new DeepResearchGraphRunner(branchExecutor, (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null)
                    .run(context(request, printer, executor), request);

            ResearchPlan plan = ResearchPlan.create(request.getQuery());
            Assert.assertEquals(4, plan.subtasks().size());
            Assert.assertEquals("PASSED", result.qualityStatus());
            Assert.assertEquals(plan.subtasks().size(), result.sourceCount());
            Assert.assertTrue(result.completed());
            Assert.assertFalse(result.markdown().contains("章节证据索引"));
            Assert.assertFalse(result.markdown().contains("证据矩阵与交叉验证"));
            for (int index = 1; index <= result.sourceCount(); index++) {
                Assert.assertTrue(result.markdown().contains("[S" + index + "] claimId="));
                Assert.assertTrue(result.markdown().contains("https://source.example/"));
            }
            Assert.assertEquals(plan.sections(), finalReportPayload(printer).get("completedSections"));
            Assert.assertTrue(branchExecutor.parallelObserved());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldDegradeWhenNoToolResultContainsRealUrl() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AgentRequest request = request("req-deep-gap", "评估一个尚无公开材料的主题");
        EvidenceBranchExecutor branchExecutor = new EvidenceBranchExecutor(false, false);
        try {
            DeepResearchResult result = new DeepResearchGraphRunner(branchExecutor, (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null)
                    .run(context(request, new RecordingPrinter(), executor), request);

            Assert.assertEquals("DEGRADED", result.qualityStatus());
            Assert.assertEquals(0, result.sourceCount());
            Assert.assertEquals(1, result.repairCount());
            Assert.assertTrue(result.markdown().contains("证据不足"));
            Assert.assertTrue(result.markdown().contains("Reviewer 定向修订"));
            Assert.assertEquals(4, branchExecutor.totalCalls());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldReviseOnlyTheSubtaskWithAnUnresolvedConflict() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AgentRequest request = request("req-deep-conflict", "比较定价模型和用户留存");
        EvidenceBranchExecutor branchExecutor = new EvidenceBranchExecutor(true, true);
        try {
            DeepResearchResult result = new DeepResearchGraphRunner(branchExecutor, (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null)
                    .run(context(request, new RecordingPrinter(), executor), request);

            Assert.assertEquals("PASSED", result.qualityStatus());
            Assert.assertEquals(1, result.repairCount());
            Assert.assertEquals(2, branchExecutor.callsFor(1));
            Assert.assertEquals(1, branchExecutor.callsFor(2));
            Assert.assertTrue(result.markdown().contains("来源对同一结论存在相反口径"));
            Assert.assertTrue(result.markdown().contains("Reviewer 定向修订"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldScopeCheckpointThreadIdByOwnerAndRequest() {
        Assert.assertNotEquals(DeepResearchGraphRunner.stableThreadId("1001", "req-same"),
                DeepResearchGraphRunner.stableThreadId("1002", "req-same"));
    }

    @Test
    public void shouldResumeCompletedCheckpointWithoutRepeatingResearchers() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AgentRequest request = request("req-deep-resume", "比较模型成本和准确性");
        EvidenceBranchExecutor branchExecutor = new EvidenceBranchExecutor(false, true);
        DeepResearchGraphRunner runner = new DeepResearchGraphRunner(branchExecutor, new MemorySaver());
        try {
            DeepResearchResult first = runner.run(context(request, new RecordingPrinter(), executor), request);
            DeepResearchResult second = runner.run(context(request, new RecordingPrinter(), executor), request);

            Assert.assertTrue(first.completed());
            Assert.assertTrue(second.completed());
            Assert.assertEquals(first.markdown(), second.markdown());
            Assert.assertEquals(2, branchExecutor.totalCalls());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldPropagateQuotaFailureInsteadOfPublishingDegradedReport() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AgentRequest request = request("req-deep-quota", "分析配额不足行为");
        RecordingPrinter printer = new RecordingPrinter();
        try {
            try {
                new DeepResearchGraphRunner((parentContext, parentRequest, plan, researcherIndex) -> {
                    throw new QuotaInsufficientException("额度不足");
                }, (org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver) null).run(context(request, printer, executor), request);
                Assert.fail("expected quota failure");
            } catch (RuntimeException expected) {
                Assert.assertTrue(hasCause(expected, QuotaInsufficientException.class));
            }
            Assert.assertFalse(printer.events.stream().anyMatch(event -> event instanceof AgentStreamEvent.StageOutput output
                    && "deep_research_report".equals(output.outputType())));
        } finally {
            executor.shutdownNow();
        }
    }

    private AgentRequest request(String requestId, String query) {
        return AgentRequest.builder().requestId(requestId).sessionId("session-deep").ownerId("1001")
                .query(query).executionMode("DEEP").outputStyle("markdown").isStream(true).build();
    }

    private AgentContext context(AgentRequest request, Printer printer, ExecutorService executor) {
        return AgentContext.builder().requestId(request.getRequestId()).sessionId(request.getSessionId())
                .ownerId(Long.valueOf(request.getOwnerId())).query(request.getQuery()).printer(printer)
                .runtimeDependencies(ReactorRuntimeDependencies.builder().reactorConfig(new ReactorConfig())
                        .fileArtifactPort(new StubFileArtifactPort()).taskExecutor(executor).build()).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> finalReportPayload(RecordingPrinter printer) {
        return printer.events.stream().filter(event -> event instanceof AgentStreamEvent.StageOutput output
                        && "deep_research_report".equals(output.outputType()) && output.isFinal())
                .map(event -> (Map<String, Object>) ((AgentStreamEvent.StageOutput) event).payload()).findFirst().orElseThrow();
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static class EvidenceBranchExecutor implements ResearchBranchExecutor {
        private final boolean conflictFirstPass;
        private final boolean evidence;
        private final Map<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final List<Long> started = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<Long> completed = java.util.Collections.synchronizedList(new ArrayList<>());

        private EvidenceBranchExecutor(boolean conflictFirstPass, boolean evidence) {
            this.conflictFirstPass = conflictFirstPass;
            this.evidence = evidence;
        }

        @Override
        public ResearchBranchResult execute(AgentContext parentContext, AgentRequest parentRequest,
                                            ResearchPlan plan, int researcherIndex) throws Exception {
            int call = calls.computeIfAbsent(researcherIndex, ignored -> new AtomicInteger()).incrementAndGet();
            long startedAt = System.nanoTime();
            started.add(startedAt);
            Thread.sleep(100);
            List<String> sections = plan.assignedSections(researcherIndex);
            String section = sections.getFirst();
            List<ResearchEvidencePacket> packets = evidence ? List.of(new ResearchEvidencePacket(
                    "claim-" + researcherIndex + "-" + call,
                    "可核验来源 " + researcherIndex,
                    "https://source.example/" + researcherIndex + "/" + call,
                    "工具返回的原始摘录，支持该子任务的有限结论。")) : List.of();
            List<String> conflicts = conflictFirstPass && researcherIndex == 1 && call == 1
                    ? List.of("来源对同一结论存在相反口径") : List.of();
            completed.add(System.nanoTime());
            return new ResearchBranchResult("researcher_" + researcherIndex, sections,
                    "### " + section + "\n\n结论严格限定在工具返回的材料范围内。", packets, conflicts,
                    packets.isEmpty() ? List.of("没有可引用 URL") : List.of(), startedAt, System.currentTimeMillis());
        }

        private int callsFor(int researcherIndex) {
            AtomicInteger value = calls.get(researcherIndex);
            return value == null ? 0 : value.get();
        }

        private int totalCalls() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        private boolean parallelObserved() {
            return started.size() > 1 && started.stream().max(Long::compareTo).orElse(0L)
                    < completed.stream().min(Long::compareTo).orElse(Long.MAX_VALUE);
        }
    }

    private static class StubFileArtifactPort implements FileArtifactPort {
        @Override
        public FileResponse upload(String serviceBaseUrl, FileRequest request) {
            return FileResponse.builder().requestId(request.getRequestId()).fileName(request.getFileName())
                    .ossUrl("oss://deep-research/" + request.getFileName())
                    .domainUrl("https://files.example.com/" + request.getFileName())
                    .fileSize(request.getContent().getBytes(StandardCharsets.UTF_8).length).build();
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
}
