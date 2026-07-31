package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactBinding;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactFormatter;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.dto.FileRequest;
import com.linrun.agent.domain.agent.runtime.dto.FileResponse;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphPort;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunHandle;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunRequest;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunResumeRequest;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunSnapshot;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded Deep Research graph. Only evidence packets with an observed HTTP(S)
 * URL can enter the report. A Reviewer can request one revision of the
 * affected subtasks; it cannot inflate a report with synthetic sources or text.
 */
@Slf4j
@Service("legacyDeepResearchGraphPort")
public class DeepResearchGraphRunner implements GraphPort {

    public static final String GRAPH_ID = "deep_research_graph";
    private static final int MAX_REPAIR_COUNT = 1;
    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]");
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]}>\\\"]+");

    private static final String PLANNER = "research_planner";
    private static final String RESEARCHERS_PARALLEL = "researchers_parallel";
    private static final String EVIDENCE_MERGER = "evidence_merger";
    private static final String REPORT_ASSEMBLER = "report_assembler";
    private static final String SYNTHESIS = "synthesis";
    private static final String REVIEWER = "reviewer";
    private static final String TARGETED_REPAIR = "targeted_repair";
    private static final String FINAL_REVIEWER = "final_reviewer";
    private static final String MARKDOWN_ARTIFACT = "markdown_artifact";
    private static final String REPAIR = "repair";
    private static final String ARTIFACT = "artifact";

    private final ResearchBranchExecutor branchExecutor;
    private final BaseCheckpointSaver checkpointSaver;
    private final DeepResearchArtifactDelivery artifactDelivery;

    @Autowired
    public DeepResearchGraphRunner(ResearchBranchExecutor branchExecutor,
                                   ObjectProvider<BaseCheckpointSaver> checkpointSaver,
                                   ObjectProvider<DeepResearchArtifactDelivery> artifactDelivery) {
        this(branchExecutor,
                checkpointSaver == null ? null : checkpointSaver.getIfAvailable(),
                artifactDelivery == null ? null : artifactDelivery.getIfAvailable());
    }

    DeepResearchGraphRunner(ResearchBranchExecutor branchExecutor,
                            BaseCheckpointSaver checkpointSaver) {
        this(branchExecutor, checkpointSaver, null);
    }

    DeepResearchGraphRunner(ResearchBranchExecutor branchExecutor,
                            BaseCheckpointSaver checkpointSaver,
                            DeepResearchArtifactDelivery artifactDelivery) {
        this.branchExecutor = Objects.requireNonNull(branchExecutor, "ResearchBranchExecutor must not be null");
        this.checkpointSaver = checkpointSaver;
        this.artifactDelivery = artifactDelivery;
    }

    public DeepResearchResult run(AgentContext context, AgentRequest request) throws Exception {
        return start(GraphRunRequest.from(context, request)).result();
    }

    @Override
    public GraphRunHandle start(GraphRunRequest graphRequest) throws Exception {
        AgentContext context = graphRequest.context();
        AgentRequest request = graphRequest.request();
        activateRunDeadline(context);
        ensureNotCancelled(context);
        String threadId = graphRequest.threadId();
        RunSession session = new RunSession(context, request, threadId);
        CompiledGraph<DeepResearchState> workflow = buildGraph(session).compile(compileConfig());
        RunnableConfig runnableConfig = runnableConfig(context, threadId);
        boolean resumed = shouldResume(workflow, runnableConfig);
        Optional<DeepResearchState> finalState = resumed
                ? workflow.invoke(GraphInput.resume(), runnableConfig)
                : workflow.invoke(initialState(request, threadId), runnableConfig);
        DeepResearchState state = finalState.orElseGet(() -> new DeepResearchState(initialState(request, threadId)));
        return new GraphRunHandle(GRAPH_ID, threadId, DeepResearchResult.from(state), resumed);
    }

    @Override
    public GraphRunSnapshot resume(GraphRunResumeRequest request) throws Exception {
        GraphRunHandle handle = start(request.request());
        return new GraphRunSnapshot(handle.graphId(), handle.threadId(),
                handle.resumed() ? "RESUMED" : "STARTED", handle.result().completed(), Instant.now());
    }

    private void activateRunDeadline(AgentContext context) {
        if (context == null || context.hasRunDeadline()) {
            return;
        }
        ReactorConfig reactorConfig = context.getRuntimeDependencies() == null
                ? null
                : context.getRuntimeDependencies().getReactorConfig();
        long durationSeconds = reactorConfig == null || reactorConfig.getAgentLoopMaxDurationSeconds() == null
                ? 900L
                : reactorConfig.getAgentLoopMaxDurationSeconds();
        context.activateRunDeadline(Math.max(1L, durationSeconds) * 1_000L);
    }

    public static String stableThreadId(String ownerId, String requestId) {
        return GraphRunRequest.stableThreadId(StringUtils.defaultString(ownerId), StringUtils.defaultString(requestId));
    }

    private StateGraph<DeepResearchState> buildGraph(RunSession session) throws Exception {
        StateGraph<DeepResearchState> graph = new StateGraph<>(channels(), DeepResearchState::new);
        graph.addNode(PLANNER, AsyncNodeAction.node_async(state -> planner(session, state)));
        graph.addNode(RESEARCHERS_PARALLEL, AsyncNodeAction.node_async(state -> researchersParallel(session, state)));
        graph.addNode(EVIDENCE_MERGER, AsyncNodeAction.node_async(state -> evidenceMerger(session, state)));
        graph.addNode(REPORT_ASSEMBLER, AsyncNodeAction.node_async(state -> reportAssembler(session, state)));
        graph.addNode(SYNTHESIS, AsyncNodeAction.node_async(state -> synthesis(session, state)));
        graph.addNode(REVIEWER, AsyncNodeAction.node_async(state -> reviewer(session, state, true)));
        graph.addNode(TARGETED_REPAIR, AsyncNodeAction.node_async(state -> targetedRepair(session, state)));
        graph.addNode(FINAL_REVIEWER, AsyncNodeAction.node_async(state -> reviewer(session, state, false)));
        graph.addNode(MARKDOWN_ARTIFACT, AsyncNodeAction.node_async(state -> markdownArtifact(session, state)));

        graph.addEdge(GraphDefinition.START, PLANNER);
        graph.addEdge(PLANNER, RESEARCHERS_PARALLEL);
        graph.addEdge(RESEARCHERS_PARALLEL, EVIDENCE_MERGER);
        graph.addEdge(EVIDENCE_MERGER, REPORT_ASSEMBLER);
        graph.addEdge(REPORT_ASSEMBLER, SYNTHESIS);
        graph.addEdge(SYNTHESIS, REVIEWER);
        graph.addConditionalEdges(REVIEWER, AsyncEdgeAction.edge_async(this::reviewRoute),
                Map.of(REPAIR, TARGETED_REPAIR, ARTIFACT, MARKDOWN_ARTIFACT));
        graph.addEdge(TARGETED_REPAIR, FINAL_REVIEWER);
        graph.addEdge(FINAL_REVIEWER, MARKDOWN_ARTIFACT);
        graph.addEdge(MARKDOWN_ARTIFACT, GraphDefinition.END);
        return graph;
    }

    private Map<String, Channel<?>> channels() {
        Map<String, Channel<?>> channels = new LinkedHashMap<>();
        channels.put(DeepResearchState.QUERY, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.OWNER_ID, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.REQUEST_ID, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.SESSION_ID, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.CHECKPOINT_THREAD_ID, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.PLAN, Channels.<Map<String, Object>>base(() -> new LinkedHashMap<>()));
        channels.put(DeepResearchState.BRANCH_RESULTS, Channels.<Map<String, Object>>appender(ArrayList::new));
        channels.put(DeepResearchState.COMPLETED_SECTIONS, Channels.<String>appender(ArrayList::new));
        channels.put(DeepResearchState.EVIDENCE, Channels.<List<Map<String, Object>>>base(() -> new ArrayList<>()));
        channels.put(DeepResearchState.MARKDOWN, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.QUALITY, Channels.<Map<String, Object>>base(() -> new LinkedHashMap<>()));
        channels.put(DeepResearchState.PLAN_REVISION, Channels.<Map<String, Object>>base(() -> new LinkedHashMap<>()));
        channels.put(DeepResearchState.REPAIR_COUNT, Channels.<Integer>base(() -> 0));
        channels.put(DeepResearchState.SUMMARY, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.REPORT_ARTIFACT_ID, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.ARTIFACT_REFS, Channels.<List<Map<String, Object>>>base(() -> new ArrayList<>()));
        return channels;
    }

    private CompileConfig compileConfig() {
        CompileConfig.Builder builder = CompileConfig.builder().graphId(GRAPH_ID).recursionLimit(32).releaseThread(false);
        if (checkpointSaver != null) {
            builder.checkpointSaver(checkpointSaver);
        }
        return builder.build();
    }

    private RunnableConfig runnableConfig(AgentContext context, String threadId) {
        RunnableConfig.Builder builder = RunnableConfig.builder().graphId(GRAPH_ID).threadId(threadId);
        Executor executor = context == null || context.getRuntimeDependencies() == null
                ? null : context.getRuntimeDependencies().getTaskExecutor();
        if (executor != null) {
            for (int index = 1; index <= 4; index++) {
                builder.addParallelNodeExecutor(researcherNode(index), executor);
            }
        }
        return builder.build();
    }

    private boolean shouldResume(CompiledGraph<DeepResearchState> workflow, RunnableConfig config) {
        return checkpointSaver != null && workflow.lastStateOf(config).isPresent();
    }

    private Map<String, Object> initialState(AgentRequest request, String threadId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(DeepResearchState.QUERY, request.getQuery());
        state.put(DeepResearchState.OWNER_ID, request.getOwnerId());
        state.put(DeepResearchState.REQUEST_ID, request.getRequestId());
        state.put(DeepResearchState.SESSION_ID, request.getSessionId());
        state.put(DeepResearchState.CHECKPOINT_THREAD_ID, threadId);
        state.put(DeepResearchState.PLAN_REVISION, ResearchPlanRevision.none().toMap());
        state.put(DeepResearchState.REPAIR_COUNT, 0);
        return state;
    }

    private Map<String, Object> planner(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        ResearchPlan plan = ResearchPlan.create(state.text(DeepResearchState.QUERY));
        emitProgress(session, PLANNER, "planner", "completed", 10, 0, List.of(),
                Map.of("subtaskCount", plan.subtasks().size(), "subtasks", plan.subtasks().stream().map(ResearchSubtask::toMap).toList()));
        return Map.of(DeepResearchState.PLAN, plan.toMap());
    }

    private Map<String, Object> researchersParallel(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        List<ResearchBranchResult> results = executeResearchers(session, state, state.plan(), "researcher", 15, 35);
        return resultsUpdate(state.plan(), results);
    }

    private List<ResearchBranchResult> executeResearchers(RunSession session,
                                                           DeepResearchState state,
                                                           ResearchPlan plan,
                                                           String role,
                                                           int startedProgress,
                                                           int completedProgress) {
        Executor executor = session.context().getRuntimeDependencies() == null
                ? null : session.context().getRuntimeDependencies().getTaskExecutor();
        List<CompletableFuture<ResearchBranchResult>> futures = new ArrayList<>();
        for (Integer researcherIndex : plan.researcherIndexes()) {
            futures.add(executor == null
                    ? CompletableFuture.supplyAsync(() -> executeResearcher(session, state, plan, researcherIndex, role,
                    startedProgress, completedProgress))
                    : CompletableFuture.supplyAsync(() -> executeResearcher(session, state, plan, researcherIndex, role,
                    startedProgress, completedProgress), executor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        ensureNotCancelled(session.context());
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private ResearchBranchResult executeResearcher(RunSession session,
                                                   DeepResearchState state,
                                                   ResearchPlan plan,
                                                   int researcherIndex,
                                                   String role,
                                                   int startedProgress,
                                                   int completedProgress) {
        ensureNotCancelled(session.context());
        emitProgress(session, researcherNode(researcherIndex), role, "running", startedProgress,
                0, List.of(), Map.of("subtasks", plan.assignedSubtasks(researcherIndex).stream()
                        .map(ResearchSubtask::toMap).toList()));
        long startedAt = System.currentTimeMillis();
        ResearchBranchResult result;
        try {
            result = branchExecutor.execute(session.context(), session.request(), plan, researcherIndex);
        } catch (Exception error) {
            QuotaInsufficientException quotaFailure = quotaFailure(error);
            if (quotaFailure != null) {
                throw quotaFailure;
            }
            log.warn("{} deep research branch failed node={} errorType={}", session.request().getRequestId(),
                    researcherNode(researcherIndex), error.getClass().getSimpleName());
            result = ResearchBranchResult.failure(researcherIndex, plan.assignedSections(researcherIndex), startedAt, error);
        }
        List<String> completed = result.evidence().stream().anyMatch(ResearchEvidencePacket::hasSource)
                ? result.assignedSections() : List.of();
        emitProgress(session, researcherNode(researcherIndex), role, "completed", completedProgress,
                validEvidence(result.evidence()).size(), completed,
                Map.of("gapCount", result.gaps().size(), "conflictCount", result.conflicts().size()));
        return result;
    }

    private Map<String, Object> resultsUpdate(ResearchPlan plan, List<ResearchBranchResult> results) {
        List<String> completed = results.stream()
                .filter(result -> !validEvidence(result.evidence()).isEmpty())
                .flatMap(result -> result.assignedSections().stream())
                .toList();
        return Map.of(
                DeepResearchState.BRANCH_RESULTS, results.stream().map(ResearchBranchResult::toMap).toList(),
                DeepResearchState.COMPLETED_SECTIONS, completed
        );
    }

    private Map<String, Object> evidenceMerger(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        List<ResearchEvidencePacket> evidence = mergeEvidence(state.branchResults());
        emitProgress(session, EVIDENCE_MERGER, "evidence_merger", "completed", 62, evidence.size(),
                orderedCompletedSections(state.plan(), state.completedSections()), Map.of());
        return Map.of(DeepResearchState.EVIDENCE, evidence.stream().map(ResearchEvidencePacket::toMap).toList());
    }

    private Map<String, Object> reportAssembler(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        String markdown = assembleMarkdown(state.plan(), state.branchResults(), state.evidence());
        emitProgress(session, REPORT_ASSEMBLER, "report_assembler", "completed", 72, state.evidence().size(),
                orderedCompletedSections(state.plan(), state.completedSections()), Map.of("previewMarkdown", preview(markdown)));
        return Map.of(DeepResearchState.MARKDOWN, markdown);
    }

    private Map<String, Object> synthesis(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        String markdown = state.text(DeepResearchState.MARKDOWN)
                + "\n\n## 综合判断\n\n"
                + "结论只覆盖下列已列真实来源能够支持的范围；证据缺口和冲突不会被解释为肯定结论。\n";
        emitProgress(session, SYNTHESIS, "synthesis", "completed", 82, state.evidence().size(),
                orderedCompletedSections(state.plan(), state.completedSections()), Map.of());
        return Map.of(DeepResearchState.MARKDOWN, markdown);
    }

    private Map<String, Object> reviewer(RunSession session, DeepResearchState state, boolean allowRevision) {
        ensureNotCancelled(session.context());
        ReportQualityResult quality = review(state.plan(), state.branchResults(), state.evidence(),
                state.text(DeepResearchState.MARKDOWN), allowRevision);
        ResearchPlanRevision revision = allowRevision && quality.requiresRepair()
                ? revisionFor(state.plan(), state.branchResults(), quality)
                : ResearchPlanRevision.none();
        String node = allowRevision ? REVIEWER : FINAL_REVIEWER;
        emitProgress(session, node, "reviewer", quality.status().toLowerCase(), allowRevision ? 88 : 94,
                quality.sourceCount(), orderedCompletedSections(state.plan(), state.completedSections()),
                Map.of("qualityStatus", quality.status(), "citationCoverage", quality.citationCoverage(),
                        "revisionTargets", revision.targetSubtaskIds()));
        return Map.of(DeepResearchState.QUALITY, quality.toMap(), DeepResearchState.PLAN_REVISION, revision.toMap());
    }

    private String reviewRoute(DeepResearchState state) {
        return state.quality().requiresRepair()
                && state.planRevision().hasTargets()
                && state.integer(DeepResearchState.REPAIR_COUNT) < MAX_REPAIR_COUNT
                ? REPAIR : ARTIFACT;
    }

    private Map<String, Object> targetedRepair(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        ResearchPlanRevision revision = state.planRevision();
        ResearchPlan revisedPlan = state.plan().revision(revision.targetSubtaskIds());
        List<ResearchBranchResult> revisedResults = executeResearchers(session, state, revisedPlan, "revision", 89, 91);
        List<ResearchBranchResult> allResults = new ArrayList<>(state.branchResults());
        allResults.addAll(revisedResults);
        List<ResearchEvidencePacket> evidence = mergeEvidence(allResults);
        String markdown = assembleMarkdown(state.plan(), allResults, evidence)
                + "\n\n## Reviewer 定向修订\n\n"
                + revision.reasons().stream().map(reason -> "- " + reason).collect(java.util.stream.Collectors.joining("\n"))
                + "\n";
        int repairCount = state.integer(DeepResearchState.REPAIR_COUNT) + 1;
        emitProgress(session, TARGETED_REPAIR, "plan_revision", "completed", 91, evidence.size(),
                orderedCompletedSections(state.plan(), state.completedSections()),
                Map.of("repairCount", repairCount, "targetSubtaskIds", revision.targetSubtaskIds()));
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(DeepResearchState.BRANCH_RESULTS, revisedResults.stream().map(ResearchBranchResult::toMap).toList());
        update.put(DeepResearchState.COMPLETED_SECTIONS, revisedResults.stream()
                .filter(result -> !validEvidence(result.evidence()).isEmpty())
                .flatMap(result -> result.assignedSections().stream()).toList());
        update.put(DeepResearchState.EVIDENCE, evidence.stream().map(ResearchEvidencePacket::toMap).toList());
        update.put(DeepResearchState.MARKDOWN, markdown);
        update.put(DeepResearchState.REPAIR_COUNT, repairCount);
        return update;
    }

    private Map<String, Object> markdownArtifact(RunSession session, DeepResearchState state) throws Exception {
        ensureNotCancelled(session.context());
        String markdown = state.text(DeepResearchState.MARKDOWN);
        ReportQualityResult quality = state.quality();
        ToolArtifactBinding canonicalBinding = uploadReport(session, markdown);
        List<ToolArtifactBinding> bindings = new ArrayList<>();
        bindings.add(canonicalBinding);
        if (artifactDelivery != null) {
            bindings.addAll(artifactDelivery.deliver(session.context(), session.request(),
                    session.checkpointThreadId(), markdown));
        }
        List<Map<String, Object>> artifactRefs = ToolArtifactFormatter.toArtifactRefs(bindings);
        String artifactId = artifactRefs.isEmpty() ? canonicalBinding.getFile().getFileName()
                : String.valueOf(artifactRefs.getFirst().get("resourceKey"));
        String summary = "深度调研报告已生成：%s；质量=%s，来源=%d，字数=%d，交付物=%d。"
                .formatted(canonicalBinding.getFile().getFileName(), quality.status(), quality.sourceCount(),
                        quality.charCount(), artifactRefs.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", MARKDOWN_ARTIFACT);
        payload.put("role", "report_artifact");
        payload.put("status", "completed");
        payload.put("progress", 100);
        payload.put("evidenceCount", quality.sourceCount());
        payload.put("completedSections", orderedCompletedSections(state.plan(), state.completedSections()));
        payload.put("reportArtifactId", artifactId);
        payload.put("qualityStatus", quality.status());
        payload.put("citationCoverage", quality.citationCoverage());
        payload.put("sourceCount", quality.sourceCount());
        payload.put("charCount", quality.charCount());
        payload.put("checkpointThreadId", session.checkpointThreadId());
        payload.put("previewMarkdown", preview(markdown));
        session.context().getPrinter().send(new AgentStreamEvent.StageOutput(session.request().getRequestId(),
                MARKDOWN_ARTIFACT, "deep_research_report", payload, artifactRefs, true));
        return Map.of(DeepResearchState.REPORT_ARTIFACT_ID, artifactId,
                DeepResearchState.SUMMARY, summary, DeepResearchState.ARTIFACT_REFS, artifactRefs);
    }

    private ToolArtifactBinding uploadReport(RunSession session, String markdown) throws Exception {
        AgentContext context = session.context();
        ReactorConfig config = context.getRuntimeDependencies().requireReactorConfig();
        FileArtifactPort port = context.getRuntimeDependencies().requireFileArtifactPort();
        String fileName = "deep_research_" + session.checkpointThreadId().replace("-", "") + ".md";
        FileRequest request = FileRequest.builder().requestId(StringUtils.defaultIfBlank(context.getSessionId(), context.getRequestId()))
                .fileName(fileName).description("深度调研 Markdown 报告").content(markdown).build();
        FileResponse response = port.upload(config.getCodeInterpreterUrl(), request);
        File file = File.builder().ossUrl(response == null ? "" : response.getOssUrl())
                .domainUrl(response == null ? "" : response.getDomainUrl()).fileName(fileName)
                .fileSize(response == null || response.getFileSize() == null
                        ? markdown.getBytes(StandardCharsets.UTF_8).length : response.getFileSize())
                .description(request.getDescription()).isInternalFile(Boolean.FALSE).build();
        return context.registerGeneratedArtifact(ToolArtifactSource.builder().sessionId(context.getSessionId())
                .requestId(context.getRequestId()).toolCallId(MARKDOWN_ARTIFACT)
                .toolName(ExecutionLedgerConstants.AGENT_NAME_DEEP_RESEARCH_GRAPH).build(), file);
    }

    private List<ResearchEvidencePacket> mergeEvidence(List<ResearchBranchResult> branchResults) {
        Map<String, ResearchEvidencePacket> deduped = new LinkedHashMap<>();
        for (ResearchBranchResult result : latestBySection(branchResults).values()) {
            for (ResearchEvidencePacket packet : validEvidence(result.evidence())) {
                deduped.putIfAbsent(packet.url(), packet);
            }
        }
        return List.copyOf(deduped.values());
    }

    private String assembleMarkdown(ResearchPlan plan,
                                    List<ResearchBranchResult> branchResults,
                                    List<ResearchEvidencePacket> evidence) {
        StringBuilder markdown = new StringBuilder("# ").append(plan.title()).append("\n\n## 执行摘要\n\n");
        if (evidence.isEmpty()) {
            markdown.append("未获得可引用的真实 HTTP(S) 来源；下文仅保留问题和证据缺口，不给出无证据结论。\n\n");
        } else {
            markdown.append("本报告的引用只来自工具实际返回且同时带有 claimId、标题、URL 与摘录的证据包。\n\n");
        }
        markdown.append("## 研究计划\n\n");
        for (ResearchSubtask task : plan.subtasks()) {
            markdown.append("- ").append(task.id()).append("：").append(task.objective())
                    .append("；工具预算=").append(task.maxToolCalls())
                    .append("；最少证据=").append(task.minimumEvidence()).append('\n');
        }
        markdown.append('\n');
        Map<String, ResearchBranchResult> latest = latestBySection(branchResults);
        for (ResearchSubtask task : plan.subtasks()) {
            markdown.append("## ").append(task.section()).append("\n\n");
            ResearchBranchResult result = latest.get(task.section());
            List<ResearchEvidencePacket> sectionEvidence = result == null ? List.of() : validEvidence(result.evidence());
            if (result == null || sectionEvidence.size() < task.minimumEvidence()) {
                markdown.append("证据不足：此子任务未达到最低真实来源要求。\n\n");
                continue;
            }
            markdown.append(sanitizeBranchMarkdown(result.markdown(), sectionEvidence).strip()).append("\n\n");
            markdown.append("已验证证据：");
            for (ResearchEvidencePacket packet : sectionEvidence) {
                markdown.append(citation(evidence, packet)).append(' ');
            }
            markdown.append("\n\n");
        }
        List<String> conflicts = historicalConflicts(branchResults);
        markdown.append("## 冲突与不确定性\n\n");
        if (conflicts.isEmpty()) {
            markdown.append("- 未报告可定位的证据冲突；这不等于不存在反例。\n\n");
        } else {
            conflicts.forEach(conflict -> markdown.append("- ").append(conflict).append('\n'));
            markdown.append("\n以上冲突限制结论为条件性判断，不能被汇总为确定性结论。\n\n");
        }
        markdown.append("## 证据与来源\n\n");
        if (evidence.isEmpty()) {
            markdown.append("- 证据不足：没有满足 URL/title/excerpt/claimId 契约的来源。\n");
        } else {
            for (int index = 0; index < evidence.size(); index++) {
                ResearchEvidencePacket packet = evidence.get(index);
                markdown.append("- [S").append(index + 1).append("] claimId=").append(packet.claimId())
                        .append("；").append(packet.title()).append(" - ").append(packet.url())
                        .append("：").append(StringUtils.abbreviate(packet.snippet(), 480)).append('\n');
            }
        }
        return markdown.toString();
    }

    private Map<String, ResearchBranchResult> latestBySection(List<ResearchBranchResult> branchResults) {
        Map<String, ResearchBranchResult> latest = new LinkedHashMap<>();
        for (ResearchBranchResult result : branchResults) {
            for (String section : result.assignedSections()) {
                latest.put(section, result);
            }
        }
        return latest;
    }

    private List<String> historicalConflicts(List<ResearchBranchResult> results) {
        return results.stream().flatMap(result -> result.conflicts().stream()).filter(StringUtils::isNotBlank)
                .distinct().toList();
    }

    private List<String> unresolvedConflicts(ResearchPlan plan, List<ResearchBranchResult> results) {
        Map<String, ResearchBranchResult> latest = latestBySection(results);
        List<String> unresolved = new ArrayList<>();
        for (ResearchSubtask task : plan.subtasks()) {
            ResearchBranchResult result = latest.get(task.section());
            if (result != null) {
                result.conflicts().stream().filter(StringUtils::isNotBlank)
                        .forEach(conflict -> unresolved.add(task.section() + "：" + conflict));
            }
        }
        return List.copyOf(unresolved);
    }

    private ReportQualityResult review(ResearchPlan plan,
                                       List<ResearchBranchResult> results,
                                       List<ResearchEvidencePacket> evidence,
                                       String markdown,
                                       boolean strict) {
        Map<String, ResearchBranchResult> latest = latestBySection(results);
        List<String> failedSections = new ArrayList<>();
        for (ResearchSubtask task : plan.subtasks()) {
            ResearchBranchResult result = latest.get(task.section());
            if (result == null || validEvidence(result.evidence()).size() < task.minimumEvidence()) {
                failedSections.add(task.section());
            }
        }
        List<String> issues = new ArrayList<>();
        if (evidence.isEmpty()) {
            issues.add("没有可引用来源");
        }
        double citationCoverage = citationCoverage(markdown, evidence.size());
        if (citationCoverage < 1D && !evidence.isEmpty()) {
            issues.add("存在未在报告中引用的来源");
        }
        List<String> unresolved = unresolvedConflicts(plan, results);
        if (!unresolved.isEmpty()) {
            issues.addAll(unresolved.stream().map(conflict -> "未解决冲突：" + conflict).toList());
        }
        int charCount = StringUtils.length(markdown);
        if (failedSections.isEmpty() && issues.isEmpty()) {
            return ReportQualityResult.passed(citationCoverage, evidence.size(), charCount);
        }
        ReportQualityResult failed = ReportQualityResult.failed(failedSections, issues, citationCoverage, evidence.size(), charCount);
        return strict ? failed : failed.degraded();
    }

    private ResearchPlanRevision revisionFor(ResearchPlan plan,
                                             List<ResearchBranchResult> results,
                                             ReportQualityResult quality) {
        LinkedHashSet<String> targets = new LinkedHashSet<>(plan.subtaskIdsForSections(quality.failedSections()));
        Map<String, ResearchBranchResult> latest = latestBySection(results);
        for (ResearchSubtask task : plan.subtasks()) {
            ResearchBranchResult result = latest.get(task.section());
            if (result != null && !result.conflicts().isEmpty()) {
                targets.add(task.id());
            }
        }
        return new ResearchPlanRevision(List.copyOf(targets), List.copyOf(quality.issues()));
    }

    private List<ResearchEvidencePacket> validEvidence(List<ResearchEvidencePacket> packets) {
        return packets == null ? List.of() : packets.stream().filter(ResearchEvidencePacket::hasSource).toList();
    }

    private String sanitizeBranchMarkdown(String branchMarkdown, List<ResearchEvidencePacket> allowedEvidence) {
        if (StringUtils.isBlank(branchMarkdown)) {
            return "证据不足。";
        }
        List<String> allowedUrls = allowedEvidence.stream().map(ResearchEvidencePacket::url).toList();
        Matcher matcher = URL.matcher(branchMarkdown);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group();
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(
                    allowedUrls.contains(url) ? url : "[未验证链接已移除]"));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    private String citation(List<ResearchEvidencePacket> evidence, ResearchEvidencePacket packet) {
        int index = evidence.indexOf(packet);
        return index < 0 ? "[未收录证据]" : "[S" + (index + 1) + "]";
    }

    private double citationCoverage(String markdown, int sourceCount) {
        if (sourceCount == 0) {
            return 0D;
        }
        LinkedHashSet<Integer> citations = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(StringUtils.defaultString(markdown));
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index > 0 && index <= sourceCount) {
                citations.add(index);
            }
        }
        return (double) citations.size() / sourceCount;
    }

    private QuotaInsufficientException quotaFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof QuotaInsufficientException quotaInsufficient) {
                return quotaInsufficient;
            }
            current = current.getCause();
        }
        return null;
    }

    private void ensureNotCancelled(AgentContext context) {
        if (context != null && context.cancellationReason() != com.linrun.agent.domain.agent.runtime.enums.AgentStopReason.NONE) {
            throw new CancellationException("deep research cancelled: " + context.cancellationReason());
        }
    }

    private List<String> orderedCompletedSections(ResearchPlan plan, List<String> completedSections) {
        LinkedHashSet<String> completed = new LinkedHashSet<>(completedSections);
        return plan.sections().stream().filter(completed::contains).toList();
    }

    private void emitProgress(RunSession session,
                              String nodeId,
                              String role,
                              String status,
                              int progress,
                              int evidenceCount,
                              List<String> completedSections,
                              Map<String, Object> extra) {
        AgentContext context = session.context();
        Printer printer = context.getPrinter();
        if (printer == null || printer.isAborted()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", nodeId);
        payload.put("role", role);
        payload.put("status", status);
        payload.put("progress", progress);
        payload.put("evidenceCount", evidenceCount);
        payload.put("completedSections", completedSections);
        payload.put("checkpointThreadId", session.checkpointThreadId());
        if (extra != null) {
            payload.putAll(extra);
        }
        printer.send(new AgentStreamEvent.StageOutput(session.request().getRequestId(), nodeId,
                "deep_research_progress", payload, List.of(), false));
    }

    private String preview(String markdown) {
        return StringUtils.abbreviate(StringUtils.defaultString(markdown), 6000);
    }

    private String researcherNode(int index) {
        return "researcher_" + index;
    }

    private record RunSession(AgentContext context, AgentRequest request, String checkpointThreadId) {
    }
}
