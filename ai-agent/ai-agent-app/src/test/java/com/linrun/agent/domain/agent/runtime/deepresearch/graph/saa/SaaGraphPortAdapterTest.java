package com.linrun.agent.domain.agent.runtime.deepresearch.graph.saa;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactBinding;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchBranchExecutor;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchBranchResult;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchPlan;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchSubtask;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.DeepResearchCheckpointState;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphCheckpointPort;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphPort;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunHandle;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunRequest;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunResumeRequest;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunSnapshot;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.InMemoryGraphCheckpointPort;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.AgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SaaGraphPortAdapterTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    public void shouldUseNativeSaaGraphByDefaultAndLeaveLegacyAsExplicitFallback() {
        contextRunner.run(context -> Assert.assertTrue(context.getBean(GraphPort.class) instanceof SaaGraphPortAdapter));
        contextRunner.withPropertyValues("aigroup.agent.graph.engine=legacy").run(context ->
                Assert.assertFalse(context.getBean(GraphPort.class) instanceof SaaGraphPortAdapter));
    }

    @Test
    public void shouldRunActualFanOutFanInWithoutStartingLegacyGraph() throws Exception {
        ConcurrentLinkedQueue<Integer> branches = new ConcurrentLinkedQueue<>();
        ResearchBranchExecutor executor = (context, request, plan, index) -> {
            branches.add(index);
            return success(plan, index);
        };
        InMemoryGraphCheckpointPort checkpoints = new InMemoryGraphCheckpointPort();
        SaaGraphPortAdapter adapter = new SaaGraphPortAdapter(executor, checkpoints);

        GraphRunHandle handle = adapter.start(request());

        Assert.assertTrue(handle.result().completed());
        Assert.assertTrue(branches.size() >= 2 && branches.size() <= 4);
        Assert.assertEquals(branches.size(), handle.result().sourceCount());
        Assert.assertEquals("SUCCEEDED", adapter.resume(new GraphRunResumeRequest(request())).status());
    }

    @Test
    public void shouldExecuteResearcherNodesConcurrentlyRatherThanUseLocalFutureFanOut() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        ResearchBranchExecutor executor = (context, request, plan, index) -> {
            int now = active.incrementAndGet();
            maximumActive.accumulateAndGet(now, Math::max);
            bothStarted.countDown();
            try {
                bothStarted.await(500, TimeUnit.MILLISECONDS);
                return success(plan, index);
            } finally {
                active.decrementAndGet();
            }
        };

        GraphRunHandle handle = new SaaGraphPortAdapter(executor, new InMemoryGraphCheckpointPort()).start(request());

        Assert.assertTrue(handle.result().completed());
        Assert.assertTrue("SAA researcher nodes must overlap", maximumActive.get() >= 2);
    }

    @Test
    public void shouldKeepOtherBranchesRunningWhenOneResearcherFails() throws Exception {
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger healthy = new AtomicInteger();
        ResearchBranchExecutor executor = (context, request, plan, index) -> {
            if (index == 1) {
                failed.incrementAndGet();
                throw new IllegalStateException("simulated branch failure");
            }
            healthy.incrementAndGet();
            return success(plan, index);
        };

        GraphRunHandle handle = new SaaGraphPortAdapter(executor, new InMemoryGraphCheckpointPort()).start(request());

        Assert.assertTrue("healthy branch must complete despite another branch failure", healthy.get() >= 1);
        Assert.assertTrue("Reviewer has one bounded repair opportunity", failed.get() <= 2);
        Assert.assertEquals(1, handle.result().repairCount());
        Assert.assertFalse(handle.result().completed());
        Assert.assertEquals("DEGRADED", handle.result().qualityStatus());
    }

    @Test
    public void shouldCompleteDeliveredReportWhenEvidenceIsExplicitlyDegraded() throws Exception {
        ResearchBranchExecutor executor = (context, request, plan, index) -> staleSuccess(plan, index);

        GraphRunHandle handle = new SaaGraphPortAdapter(executor, new InMemoryGraphCheckpointPort()).start(request());

        Assert.assertTrue("an uncertainty-marked report is still a completed delivery", handle.result().completed());
        Assert.assertEquals("DEGRADED", handle.result().qualityStatus());
    }

    @Test
    public void shouldRepairOnlyReviewerTargetAndOnlyOnce() throws Exception {
        AtomicInteger firstBranch = new AtomicInteger();
        AtomicInteger secondBranch = new AtomicInteger();
        ResearchBranchExecutor executor = (context, request, plan, index) -> {
            if (index == 1 && firstBranch.incrementAndGet() == 1) {
                String section = plan.assignedSections(index).getFirst();
                return new ResearchBranchResult("researcher_1", List.of(section), "initial incomplete result",
                        List.of(), List.of(), List.of("missing source"), 1L, 2L);
            }
            if (index == 2) {
                secondBranch.incrementAndGet();
            }
            return success(plan, index);
        };

        GraphRunHandle handle = new SaaGraphPortAdapter(executor, new InMemoryGraphCheckpointPort()).start(request());

        Assert.assertTrue(handle.result().completed());
        Assert.assertEquals(2, firstBranch.get());
        Assert.assertEquals("unaffected researcher must not run during repair", 1, secondBranch.get());
        Assert.assertEquals(1, handle.result().repairCount());
    }

    @Test
    public void shouldResumeSafeCheckpointWithoutReplayingCompletedResearchBranches() throws Exception {
        ResearchPlan plan = ResearchPlan.create("市场规模，监管风险");
        ResearchBranchResult first = success(plan, 1);
        ResearchBranchResult second = success(plan, 2);
        Map<String, Object> checkpointState = recoveryState(plan, List.of(first, second),
                Map.of("1", "SUCCEEDED", "2", "SUCCEEDED"));
        InMemoryGraphCheckpointPort checkpoints = new InMemoryGraphCheckpointPort();
        checkpoints.save(new GraphRunSnapshot(SaaGraphPortAdapter.GRAPH_ID, request().threadId(), "RUNNING", false,
                Instant.now(), checkpointState));
        AtomicInteger replayed = new AtomicInteger();
        SaaGraphPortAdapter adapter = new SaaGraphPortAdapter((context, request, restoredPlan, index) -> {
            replayed.incrementAndGet();
            return success(restoredPlan, index);
        }, checkpoints);

        GraphRunSnapshot resumed = adapter.resume(new GraphRunResumeRequest(request()));

        Assert.assertEquals(0, replayed.get());
        Assert.assertTrue(resumed.terminal());
        Assert.assertEquals("SUCCEEDED", resumed.status());
        List<?> persistedBranches = (List<?>) resumed.checkpointState().get(DeepResearchCheckpointState.BRANCH_RESULTS);
        Assert.assertEquals("", String.valueOf(((Map<?, ?>) persistedBranches.getFirst()).get("markdown")));
    }

    @Test
    public void shouldNotAutomaticallyReplayNonIdempotentCrashWindow() throws Exception {
        ResearchPlan plan = ResearchPlan.create("市场规模，监管风险");
        ResearchBranchResult second = success(plan, 2);
        Map<String, Object> checkpointState = recoveryState(plan, List.of(second),
                Map.of("1", "IN_FLIGHT_NON_IDEMPOTENT", "2", "SUCCEEDED"));
        InMemoryGraphCheckpointPort checkpoints = new InMemoryGraphCheckpointPort();
        checkpoints.save(new GraphRunSnapshot(SaaGraphPortAdapter.GRAPH_ID, request().threadId(), "RUNNING", false,
                Instant.now(), checkpointState));
        AtomicInteger invoked = new AtomicInteger();
        SaaGraphPortAdapter adapter = new SaaGraphPortAdapter((context, request, restoredPlan, index) -> {
            invoked.incrementAndGet();
            return success(restoredPlan, index);
        }, checkpoints);

        GraphRunSnapshot resumed = adapter.resume(new GraphRunResumeRequest(request()));

        Assert.assertEquals("crash-window branch must be reconciled, not replayed", 0, invoked.get());
        Assert.assertTrue(resumed.terminal());
        Assert.assertEquals("DEGRADED", resumed.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> branches = (Map<String, Object>) resumed.checkpointState().get(DeepResearchCheckpointState.BRANCH_EXECUTION);
        Assert.assertEquals("MANUAL_RECONCILIATION_REQUIRED", branches.get("1"));
    }

    @Test
    public void shouldRejectEmptyDuplicateUnauthorizedAndOverBudgetPlans() {
        ResearchSubtask duplicateOne = new ResearchSubtask("same", "A", "same objective", List.of("deep_search"), 4, "claims[]", 1);
        ResearchSubtask duplicateTwo = new ResearchSubtask("same", "B", "same objective", List.of("root_shell"), 4, "claims[]", 1);
        ResearchSubtask overBudget = new ResearchSubtask("third", "C", "third objective", List.of("web_fetch"), 4, "claims[]", 1);
        ResearchSubtask overBudgetFour = new ResearchSubtask("fourth", "D", "fourth objective", List.of("search_web"), 4, "claims[]", 1);
        ResearchPlan invalid = new ResearchPlan("", List.of("A", "B", "C", "D"), List.of(),
                Map.of(1, List.of("A"), 2, List.of("B"), 3, List.of("C"), 4, List.of("D")),
                List.of(duplicateOne, duplicateTwo, overBudget, overBudgetFour));

        List<String> failures = SaaGraphPortAdapter.validationFailures(invalid);

        Assert.assertTrue(failures.stream().anyMatch(value -> value.contains("title")));
        Assert.assertTrue(failures.stream().anyMatch(value -> value.contains("duplicate")));
        Assert.assertTrue(failures.stream().anyMatch(value -> value.contains("unauthorized")));
        Assert.assertTrue(failures.stream().anyMatch(value -> value.contains("exceeds tool budget")));
    }

    @Test
    public void shouldBindEveryGraphNodeToHarnessAndDeliverConfiguredArtifact() throws Exception {
        AgentHarnessFacade harness = Mockito.mock(AgentHarnessFacade.class);
        SaaGraphPortAdapter adapter = new SaaGraphPortAdapter((context, request, plan, index) -> success(plan, index),
                new InMemoryGraphCheckpointPort());
        adapter.setAgentHarnessFacade(harness);
        ToolArtifactBinding delivered = ToolArtifactBinding.builder()
                .source(ToolArtifactSource.builder().requestId("p40-saa-graph").sessionId("session-p40")
                        .toolCallId("delivery").toolName("report_tool").build())
                .file(File.builder().fileName("research.html").domainUrl("https://artifact.example/research.html")
                        .fileSize(10).description("rendered report").isInternalFile(Boolean.FALSE).build())
                .build();
        adapter.setArtifactDelivery((context, request, threadId, markdown) -> List.of(delivered));

        GraphRunHandle handle = adapter.start(request());

        Mockito.verify(harness, Mockito.atLeast(12)).bind(Mockito.any(AgentContext.class));
        Mockito.verify(harness, Mockito.atLeast(12)).projectContext(Mockito.any(AgentContext.class));
        Assert.assertTrue(handle.result().reportArtifactId().contains("research.html"));
        Assert.assertEquals(1, handle.result().artifactRefs().size());
    }

    @Test
    public void shouldEmitCanonicalDeepResearchReportAfterNativeArtifactDelivery() throws Exception {
        ConcurrentLinkedQueue<AgentStreamEvent> events = new ConcurrentLinkedQueue<>();
        SaaGraphPortAdapter adapter = new SaaGraphPortAdapter((context, request, plan, index) -> success(plan, index),
                new InMemoryGraphCheckpointPort());
        ToolArtifactBinding delivered = ToolArtifactBinding.builder()
                .source(ToolArtifactSource.builder().requestId("p100-saa-event").sessionId("session-p100")
                        .toolCallId("delivery").toolName("report_tool").build())
                .file(File.builder().fileName("research.md").domainUrl("https://artifact.example/research.md")
                        .fileSize(10).description("rendered report").isInternalFile(Boolean.FALSE).build())
                .build();
        adapter.setArtifactDelivery((context, request, threadId, markdown) -> List.of(delivered));
        AgentRequest request = AgentRequest.builder().requestId("p100-saa-event").sessionId("session-p100")
                .ownerId("1001").query("market research").executionMode("DEEP").build();
        AgentContext context = AgentContext.builder().requestId(request.getRequestId()).sessionId(request.getSessionId())
                .ownerId(1001L).query(request.getQuery()).printer(new Printer() {
                    @Override public void send(AgentStreamEvent event) { events.add(event); }
                    @Override public void close() { }
                    @Override public boolean isAborted() { return false; }
                }).build();

        adapter.start(GraphRunRequest.from(context, request));

        Assert.assertTrue(events.stream().anyMatch(event -> event instanceof AgentStreamEvent.StageOutput output
                && "deep_research_report".equals(output.outputType())
                && output.isFinal() && output.artifactRefs().size() == 1));
    }

    @Test
    public void shouldRejectNewWorkWhenParentHasAlreadyBeenCancelled() {
        GraphRunRequest request = request();
        request.context().cancel(AgentStopReason.RUN_CANCELLED);
        AtomicInteger invoked = new AtomicInteger();
        SaaGraphPortAdapter adapter = new SaaGraphPortAdapter((context, ignored, plan, index) -> {
            invoked.incrementAndGet();
            return success(plan, index);
        }, new InMemoryGraphCheckpointPort());

        Assert.assertThrows(Exception.class, () -> adapter.start(request));
        Assert.assertEquals(0, invoked.get());
    }

    private ResearchBranchResult success(ResearchPlan plan, int index) {
        String section = plan.assignedSections(index).getFirst();
        return new ResearchBranchResult("researcher_" + index, List.of(section), "证据支持的结论。",
                List.of(new ResearchEvidencePacket("claim-" + index, "source-" + index,
                        "https://source.example/" + index, "verified excerpt")), List.of(), List.of(), 1L, 2L);
    }

    private ResearchBranchResult staleSuccess(ResearchPlan plan, int index) {
        String section = plan.assignedSections(index).getFirst();
        ResearchEvidencePacket evidence = new ResearchEvidencePacket("claim-stale-" + index, "source-stale-" + index,
                "https://source.example/stale/" + index, "verified excerpt", "evidence-stale-" + index,
                "hash-stale-" + index, 1_000L, 0L, "FETCHED_PAGE", "HIGH", "STALE", "trace-stale-" + index,
                "verified claim " + index, "SUPPORTS", 0, "verified excerpt".length(), false);
        return new ResearchBranchResult("researcher_" + index, List.of(section), "证据支持的结论。",
                List.of(evidence), List.of(), List.of(), 1L, 2L);
    }

    private Map<String, Object> recoveryState(ResearchPlan plan,
                                              List<ResearchBranchResult> branches,
                                              Map<String, String> status) {
        List<ResearchEvidencePacket> evidence = branches.stream().flatMap(branch -> branch.evidence().stream()).toList();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(DeepResearchCheckpointState.QUERY, "市场规模，监管风险");
        state.put(DeepResearchCheckpointState.REQUEST_ID, "p40-saa-graph");
        state.put(DeepResearchCheckpointState.OWNER_ID, "1001");
        state.put(DeepResearchCheckpointState.SESSION_ID, "session-p40");
        state.put(DeepResearchCheckpointState.PLAN, plan.toMap());
        state.put(DeepResearchCheckpointState.SUBTASKS, plan.subtasks().stream().map(ResearchSubtask::toMap).toList());
        state.put(DeepResearchCheckpointState.BRANCH_RESULTS, branches.stream().map(ResearchBranchResult::toMap).toList());
        state.put(DeepResearchCheckpointState.BRANCH_EXECUTION, status);
        state.put(DeepResearchCheckpointState.EVIDENCE, evidence.stream().map(ResearchEvidencePacket::toMap).toList());
        state.put(DeepResearchCheckpointState.REPAIR_COUNT, 0);
        state.put(DeepResearchCheckpointState.CONTEXT_SNAPSHOT, Map.of("mode", "DEEP"));
        state.put(DeepResearchCheckpointState.QUOTA_USAGE, Map.of());
        return state;
    }

    private GraphRunRequest request() {
        AgentRequest request = AgentRequest.builder().requestId("p40-saa-graph").sessionId("session-p40")
                .ownerId("1001").query("市场规模，监管风险").executionMode("DEEP").build();
        return GraphRunRequest.from(AgentContext.builder().requestId(request.getRequestId()).sessionId(request.getSessionId())
                .ownerId(1001L).query(request.getQuery()).build(), request);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SaaGraphPortAdapter.class)
    static class Config {
        @Bean("legacyDeepResearchGraphPort") GraphPort legacy() { return new GraphPort() {
            public GraphRunHandle start(GraphRunRequest request) { throw new AssertionError("native SAA must not delegate to legacy"); }
            public GraphRunSnapshot resume(GraphRunResumeRequest request) { return new GraphRunSnapshot("legacy", request.request().threadId(), "LEGACY", false, Instant.now()); }
        }; }
        @Bean GraphCheckpointPort graphCheckpointPort() { return new InMemoryGraphCheckpointPort(); }
        @Bean ResearchBranchExecutor researchBranchExecutor() { return (context, request, plan, index) -> {
            String section = plan.assignedSections(index).getFirst();
            return new ResearchBranchResult("researcher_" + index, List.of(section), "evidence",
                    List.of(new ResearchEvidencePacket("claim-" + index, "source", "https://source.example/" + index, "excerpt")), List.of(), List.of(), 1L, 2L);
        }; }
    }
}
