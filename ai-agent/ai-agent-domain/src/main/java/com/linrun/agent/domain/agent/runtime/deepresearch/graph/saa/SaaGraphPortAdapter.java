package com.linrun.agent.domain.agent.runtime.deepresearch.graph.saa;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactBinding;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactFormatter;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.deepresearch.DeepResearchArtifactDelivery;
import com.linrun.agent.domain.agent.runtime.deepresearch.DeepResearchResult;
import com.linrun.agent.domain.agent.runtime.deepresearch.ReportQualityResult;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchBranchExecutor;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchBranchResult;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchPlan;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchPlanRevision;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchSubtask;
import com.linrun.agent.domain.agent.runtime.deepresearch.evidence.ResearchEvidenceLedger;
import com.linrun.agent.domain.agent.runtime.deepresearch.evidence.ResearchEvidenceMerger;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.DeepResearchCheckpointState;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphCheckpointPort;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphPort;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunHandle;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunRequest;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunResumeRequest;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunSnapshot;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.dto.FileRequest;
import com.linrun.agent.domain.agent.runtime.dto.FileResponse;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.AgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.deepresearch.report.CitationGate;
import com.linrun.agent.domain.agent.runtime.deepresearch.report.ReportSpec;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native Spring AI Alibaba DEEP graph. Graph nodes only orchestrate bounded
 * state; Researcher model/tool work remains behind {@link AgentHarnessFacade}
 * through {@link ResearchBranchExecutor}. The legacy LangGraph port is an
 * explicit compatibility fallback and is never invoked from this class.
 */
@Service
@Primary
@ConditionalOnProperty(name = "aigroup.agent.graph.engine", havingValue = "saa", matchIfMissing = true)
public class SaaGraphPortAdapter implements GraphPort {

    public static final String GRAPH_ID = "researchpilot_saa_deep";
    private static final int MAX_REPAIR_COUNT = 1;
    private static final int MAX_TOTAL_TOOL_CALLS = 12;
    private static final List<String> ALLOWED_RESEARCH_TOOLS = List.of(
            "search_web", "fetch_page", "extract_evidence", "analyze_file", "deep_search", "web_fetch");
    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]");
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]}>\\\"]+");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:19|20)(\\d{2})(?!\\d)");

    private static final String INTAKE = "intake";
    private static final String PLANNER = "planner";
    private static final String PLAN_VALIDATOR = "plan_validator";
    private static final String FAN_OUT = "research_fan_out";
    private static final String EVIDENCE_MERGER = "evidence_merger";
    private static final String WRITER = "writer";
    private static final String REVIEWER = "reviewer";
    private static final String TARGETED_REPAIR = "targeted_repair";
    private static final String FINAL_REVIEWER = "final_reviewer";
    private static final String REPORT_SPEC = "report_spec";

    private final ResearchBranchExecutor branchExecutor;
    private final GraphCheckpointPort checkpointPort;
    private volatile DeepResearchArtifactDelivery artifactDelivery;
    private volatile AgentHarnessFacade agentHarnessFacade;
    private volatile ResearchEvidenceLedger evidenceLedger;
    private final ResearchEvidenceMerger evidenceMerger = new ResearchEvidenceMerger();
    private final CitationGate citationGate = new CitationGate();

    public SaaGraphPortAdapter(ResearchBranchExecutor branchExecutor,
                               GraphCheckpointPort checkpointPort) {
        this.branchExecutor = Objects.requireNonNull(branchExecutor, "ResearchBranchExecutor must not be null");
        this.checkpointPort = Objects.requireNonNull(checkpointPort, "GraphCheckpointPort must not be null");
    }

    @Autowired(required = false)
    void setArtifactDelivery(DeepResearchArtifactDelivery artifactDelivery) {
        this.artifactDelivery = artifactDelivery;
    }

    @Autowired(required = false)
    void setAgentHarnessFacade(AgentHarnessFacade agentHarnessFacade) {
        this.agentHarnessFacade = agentHarnessFacade;
    }

    @Autowired(required = false)
    void setEvidenceLedger(ResearchEvidenceLedger evidenceLedger) {
        this.evidenceLedger = evidenceLedger;
    }

    @Override
    public GraphRunHandle start(GraphRunRequest request) throws Exception {
        Optional<GraphRunSnapshot> existing = checkpointPort.find(GRAPH_ID, request.threadId());
        if (existing.isPresent() && existing.get().terminal()) {
            NativeSession terminalSession = new NativeSession(request, existing.get().checkpointState());
            return new GraphRunHandle(GRAPH_ID, request.threadId(), toResult(existing.get().checkpointState(), terminalSession), true);
        }
        return execute(request, existing.map(GraphRunSnapshot::checkpointState).orElse(Map.of()), existing.isPresent());
    }

    @Override
    public GraphRunSnapshot resume(GraphRunResumeRequest request) throws Exception {
        Optional<GraphRunSnapshot> existing = checkpointPort.find(GRAPH_ID, request.request().threadId());
        if (existing.isEmpty()) {
            return new GraphRunSnapshot(GRAPH_ID, request.request().threadId(), "RESUME_NOT_FOUND", false, Instant.now());
        }
        if (existing.get().terminal()) {
            return existing.get();
        }
        execute(request.request(), existing.get().checkpointState(), true);
        return checkpointPort.find(GRAPH_ID, request.request().threadId())
                .orElseThrow(() -> new IllegalStateException("SAA graph resume did not persist a checkpoint"));
    }

    private GraphRunHandle execute(GraphRunRequest request,
                                   Map<String, Object> checkpointState,
                                   boolean resumed) throws Exception {
        NativeSession session = new NativeSession(request, checkpointState);
        CompiledGraph graph = buildGraph(session);
        RunnableConfig config = RunnableConfig.builder().threadId(request.threadId()).build();
        Map<String, Object> output = graph.invoke(initialState(request, checkpointState), config).orElseThrow().data();
        DeepResearchResult result = toResult(output, session);
        checkpoint(session, output, "COMPLETED", result.completed() ? "SUCCEEDED" : "DEGRADED", true);
        return new GraphRunHandle(GRAPH_ID, request.threadId(), result, resumed);
    }

    private CompiledGraph buildGraph(NativeSession session) throws Exception {
        StateGraph graph = new StateGraph();
        graph.addNode(INTAKE, AsyncNodeAction.node_async(state -> intake(session, state.data())));
        graph.addNode(PLANNER, AsyncNodeAction.node_async(state -> planner(session, state.data())));
        graph.addNode(PLAN_VALIDATOR, AsyncNodeAction.node_async(state -> validatePlan(session, state.data())));
        graph.addNode(FAN_OUT, AsyncNodeAction.node_async(state -> fanOut(session, state.data())));
        for (int index = 1; index <= 4; index++) {
            int researcherIndex = index;
            graph.addNode(researcherNode(index), AsyncNodeAction.node_async(
                    state -> researcher(session, state.data(), researcherIndex)));
        }
        graph.addNode(EVIDENCE_MERGER, AsyncNodeAction.node_async(state -> mergeEvidence(session, state.data())));
        graph.addNode(WRITER, AsyncNodeAction.node_async(state -> writeReport(session, state.data())));
        graph.addNode(REVIEWER, AsyncNodeAction.node_async(state -> review(session, state.data(), true)));
        graph.addNode(TARGETED_REPAIR, AsyncNodeAction.node_async(state -> repair(session, state.data())));
        graph.addNode(FINAL_REVIEWER, AsyncNodeAction.node_async(state -> review(session, state.data(), false)));
        graph.addNode(REPORT_SPEC, AsyncNodeAction.node_async(state -> reportSpec(session, state.data())));

        graph.addEdge(StateGraph.START, INTAKE);
        graph.addEdge(INTAKE, PLANNER);
        graph.addEdge(PLANNER, PLAN_VALIDATOR);
        graph.addEdge(PLAN_VALIDATOR, FAN_OUT);
        graph.addEdge(FAN_OUT, List.of(researcherNode(1), researcherNode(2), researcherNode(3), researcherNode(4)));
        graph.addEdge(List.of(researcherNode(1), researcherNode(2), researcherNode(3), researcherNode(4)), EVIDENCE_MERGER);
        graph.addEdge(EVIDENCE_MERGER, WRITER);
        graph.addEdge(WRITER, REVIEWER);
        graph.addConditionalEdges(REVIEWER, AsyncEdgeAction.edge_async(state ->
                        Boolean.TRUE.equals(state.value("repairRequired").orElse(false)) ? "repair" : "report"),
                Map.of("repair", TARGETED_REPAIR, "report", REPORT_SPEC));
        graph.addEdge(TARGETED_REPAIR, FINAL_REVIEWER);
        graph.addEdge(FINAL_REVIEWER, REPORT_SPEC);
        graph.addEdge(REPORT_SPEC, StateGraph.END);
        return graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(MemorySaver.builder().build()).build()).build());
    }

    private Map<String, Object> initialState(GraphRunRequest request, Map<String, Object> checkpointState) {
        Map<String, Object> state = new LinkedHashMap<>();
        if (checkpointState != null) {
            state.putAll(checkpointState);
        }
        state.putIfAbsent(DeepResearchCheckpointState.QUERY, request.request().getQuery());
        state.putIfAbsent(DeepResearchCheckpointState.REQUEST_ID, request.request().getRequestId());
        state.putIfAbsent(DeepResearchCheckpointState.OWNER_ID, request.request().getOwnerId());
        state.putIfAbsent(DeepResearchCheckpointState.SESSION_ID, request.request().getSessionId());
        state.putIfAbsent(DeepResearchCheckpointState.REPAIR_COUNT, 0);
        state.putIfAbsent(DeepResearchCheckpointState.BRANCH_RESULTS, List.of());
        state.putIfAbsent(DeepResearchCheckpointState.BRANCH_EXECUTION, Map.of());
        state.putIfAbsent(DeepResearchCheckpointState.CONTEXT_SNAPSHOT,
                Map.of("mode", "DEEP", "threadId", request.threadId()));
        state.putIfAbsent(DeepResearchCheckpointState.QUOTA_USAGE, Map.of());
        return Map.copyOf(state);
    }

    private Map<String, Object> intake(NativeSession session, Map<String, Object> state) {
        prepareNode(session, INTAKE);
        Map<String, Object> update = Map.of("phase", INTAKE,
                DeepResearchCheckpointState.CHECKPOINT_METADATA,
                Map.of("threadId", session.request.threadId(), "phase", INTAKE, "recoveryPolicy", "SAFE_NODES_ONLY"));
        return checkpointed(session, state, update, INTAKE, "RUNNING", false);
    }

    private Map<String, Object> planner(NativeSession session, Map<String, Object> state) {
        prepareNode(session, PLANNER);
        ResearchPlan plan = state.get(DeepResearchCheckpointState.PLAN) instanceof Map<?, ?>
                ? ResearchPlan.from(state.get(DeepResearchCheckpointState.PLAN), text(state.get(DeepResearchCheckpointState.QUERY)))
                : ResearchPlan.create(text(state.get(DeepResearchCheckpointState.QUERY)));
        session.plan = plan;
        Map<String, Object> update = Map.of(DeepResearchCheckpointState.PLAN, plan.toMap(),
                DeepResearchCheckpointState.SUBTASKS, plan.subtasks().stream().map(ResearchSubtask::toMap).toList(),
                "phase", PLANNER);
        return checkpointed(session, state, update, PLANNER, "RUNNING", false);
    }

    private Map<String, Object> validatePlan(NativeSession session, Map<String, Object> state) {
        prepareNode(session, PLAN_VALIDATOR);
        List<String> failures = validationFailures(plan(state));
        if (!failures.isEmpty()) {
            checkpoint(session, state, PLAN_VALIDATOR, "PLAN_REJECTED", true);
            throw new IllegalArgumentException("Deep Research plan rejected: " + failures);
        }
        return checkpointed(session, state, Map.of("phase", PLAN_VALIDATOR), PLAN_VALIDATOR, "RUNNING", false);
    }

    private Map<String, Object> fanOut(NativeSession session, Map<String, Object> state) {
        prepareNode(session, FAN_OUT);
        return checkpointed(session, state, Map.of("fanOut", true, "phase", FAN_OUT), FAN_OUT, "RUNNING", false);
    }

    /** Package-visible for contract tests that exercise invalid persisted plans. */
    static List<String> validationFailures(ResearchPlan plan) {
        List<String> failures = new ArrayList<>();
        if (plan == null) {
            return List.of("plan title is blank");
        }
        if (StringUtils.isBlank(plan.title())) {
            failures.add("plan title is blank");
        }
        if (plan.subtasks().size() < 2 || plan.subtasks().size() > 4) {
            failures.add("subtask count must be 2..4");
        }
        int totalToolCalls = 0;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<String> objectives = new LinkedHashSet<>();
        for (ResearchSubtask task : plan.subtasks()) {
            if (task == null || StringUtils.isAnyBlank(task.id(), task.section(), task.objective(), task.outputSchema(),
                    task.evidenceCutoff(), task.evaluationCriteria())) {
                failures.add("subtask is incomplete");
                continue;
            }
            if (!ids.add(task.id())) {
                failures.add("duplicate subtask id=" + task.id());
            }
            if (!objectives.add(StringUtils.normalizeSpace(task.objective()))) {
                failures.add("duplicate subtask objective=" + task.objective());
            }
            if (task.queryTerms().isEmpty() || task.evidenceRequirements().isEmpty()) {
                failures.add("subtask is missing query/evidence contract=" + task.id());
            }
            if (task.maxToolCalls() < 1 || task.maxToolCalls() > 4 || task.minimumEvidence() < 1) {
                failures.add("invalid budget/evidence requirement=" + task.id());
            }
            if (task.allowedTools().isEmpty() || task.allowedTools().stream().anyMatch(tool -> !ALLOWED_RESEARCH_TOOLS.contains(tool))) {
                failures.add("unauthorized tool in subtask=" + task.id());
            }
            totalToolCalls += task.maxToolCalls();
        }
        if (totalToolCalls > MAX_TOTAL_TOOL_CALLS) {
            failures.add("plan exceeds tool budget=" + totalToolCalls);
        }
        return List.copyOf(failures);
    }

    private Map<String, Object> researcher(NativeSession session, Map<String, Object> state, int index) {
        ResearchPlan plan = plan(state);
        prepareNode(session, researcherNode(index));
        if (!plan.researcherIndexes().contains(index)) {
            return checkpointed(session, state, Map.of(branchKey(index), Map.of("skipped", true)),
                    researcherNode(index), "RUNNING", false);
        }
        String priorStatus = session.branchStatus(index);
        ResearchBranchResult prior = session.branchResults.get(index);
        if (isCompletedBranchStatus(priorStatus) && prior != null) {
            return checkpointed(session, state, Map.of(branchKey(index), prior.toMap(), "phase", researcherNode(index)),
                    researcherNode(index), "RUNNING", false);
        }
        if (isUnknownInFlight(priorStatus)) {
            ResearchBranchResult manual = ResearchBranchResult.failure(index, plan.assignedSections(index), System.currentTimeMillis(),
                    new IllegalStateException("non-idempotent branch crash window requires manual reconciliation"));
            session.branchResults.put(index, manual);
            session.branchExecution.put(index, "MANUAL_RECONCILIATION_REQUIRED");
            return checkpointed(session, state, Map.of(branchKey(index), manual.toMap(), "phase", researcherNode(index)),
                    researcherNode(index), "DEGRADED", false);
        }

        session.branchExecution.put(index, "IN_FLIGHT_NON_IDEMPOTENT");
        checkpoint(session, state, researcherNode(index), "RUNNING", false);
        long started = System.currentTimeMillis();
        ResearchBranchResult result;
        try {
            result = branchExecutor.execute(session.request.context(), session.request.request(), plan, index);
            session.branchExecution.put(index, "SUCCEEDED");
        } catch (QuotaInsufficientException fatal) {
            session.branchExecution.put(index, "FAILED_QUOTA");
            throw fatal;
        } catch (Exception recoverable) {
            result = ResearchBranchResult.failure(index, plan.assignedSections(index), started, recoverable);
            session.branchExecution.put(index, "FAILED");
        }
        session.branchResults.put(index, result);
        return checkpointed(session, state, Map.of(branchKey(index), result.toMap(), "phase", researcherNode(index)),
                researcherNode(index), "RUNNING", false);
    }

    private Map<String, Object> mergeEvidence(NativeSession session, Map<String, Object> state) {
        prepareNode(session, EVIDENCE_MERGER);
        List<ResearchBranchResult> branches = session.currentBranchResults();
        ResearchEvidenceMerger.MergeResult mergedEvidence = mergeEvidenceResult(branches);
        persistEvidenceLedger(session, mergedEvidence);
        List<ResearchEvidencePacket> evidence = mergedEvidence.evidence();
        Map<String, Object> update = Map.of(DeepResearchCheckpointState.BRANCH_RESULTS,
                branches.stream().map(ResearchBranchResult::toMap).toList(),
                DeepResearchCheckpointState.EVIDENCE, evidence.stream().map(ResearchEvidencePacket::toMap).toList(),
                DeepResearchCheckpointState.CLAIM_GRAPH, claimGraph(evidence),
                DeepResearchCheckpointState.RESEARCH_METRICS, researchMetrics(session, mergedEvidence),
                "phase", EVIDENCE_MERGER);
        return checkpointed(session, state, update, EVIDENCE_MERGER, "RUNNING", false);
    }

    private Map<String, Object> writeReport(NativeSession session, Map<String, Object> state) {
        prepareNode(session, WRITER);
        ReportSpec existing = ReportSpec.from(state.get(DeepResearchCheckpointState.REPORT_SPEC));
        if (!existing.claims().isEmpty() || !existing.citations().isEmpty()) {
            return checkpointed(session, state, Map.of("phase", WRITER), WRITER, "RUNNING", false);
        }
        ResearchPlan plan = plan(state);
        List<ResearchBranchResult> branches = branchResults(session, state);
        List<ResearchEvidencePacket> evidence = evidence(state, branches);
        ReportSpec reportSpec = ReportSpec.fromResearch(plan, evidence);
        // This is a bounded review projection, not the final rendered artifact.
        return checkpointed(session, state, Map.of(DeepResearchCheckpointState.REPORT_SPEC, reportSpec.toMap(),
                        DeepResearchCheckpointState.MARKDOWN, reportSpec.reviewMarkdown(), "phase", WRITER),
                WRITER, "RUNNING", false);
    }

    private Map<String, Object> review(NativeSession session, Map<String, Object> state, boolean allowRepair) {
        String node = allowRepair ? REVIEWER : FINAL_REVIEWER;
        prepareNode(session, node);
        ResearchPlan plan = plan(state);
        List<ResearchBranchResult> branches = branchResults(session, state);
        List<ResearchEvidencePacket> evidence = evidence(state, branches);
        ReportQualityResult raw = reviewQuality(plan, branches, evidence, text(state.get(DeepResearchCheckpointState.MARKDOWN)),
                session.branchExecution);
        ReportQualityResult quality = allowRepair || !raw.requiresRepair() ? raw : raw.degraded();
        ResearchPlanRevision revision = allowRepair && quality.requiresRepair()
                ? revisionFor(plan, branches, quality, session.branchExecution)
                : ResearchPlanRevision.none();
        boolean repairRequired = allowRepair && quality.requiresRepair() && revision.hasTargets()
                && integer(state.get(DeepResearchCheckpointState.REPAIR_COUNT)) < MAX_REPAIR_COUNT;
        Map<String, Object> update = Map.of(DeepResearchCheckpointState.QUALITY, quality.toMap(),
                DeepResearchCheckpointState.REVIEW_FINDINGS, quality.issues(),
                DeepResearchCheckpointState.REPAIR_TARGETS, revision.targetSubtaskIds(),
                "repairRequired", repairRequired,
                "phase", node);
        return checkpointed(session, state, update, node, quality.passed() ? "RUNNING" : "DEGRADED", false);
    }

    private Map<String, Object> repair(NativeSession session, Map<String, Object> state) {
        prepareNode(session, TARGETED_REPAIR);
        int repairCount = integer(state.get(DeepResearchCheckpointState.REPAIR_COUNT));
        if (repairCount >= MAX_REPAIR_COUNT) {
            return checkpointed(session, state, Map.of("repairRequired", false, "phase", TARGETED_REPAIR),
                    TARGETED_REPAIR, "DEGRADED", false);
        }
        ResearchPlanRevision revision = new ResearchPlanRevision(strings(state.get(DeepResearchCheckpointState.REPAIR_TARGETS)),
                strings(state.get(DeepResearchCheckpointState.REVIEW_FINDINGS)));
        ResearchPlan revisedPlan = plan(state).revision(revision.targetSubtaskIds());
        if (!revision.hasTargets() || revisedPlan.researcherIndexes().isEmpty()) {
            return checkpointed(session, state, Map.of("repairRequired", false, "phase", TARGETED_REPAIR),
                    TARGETED_REPAIR, "DEGRADED", false);
        }

        session.repairInProgress = true;
        checkpoint(session, state, TARGETED_REPAIR, "RUNNING", false);
        for (Integer index : revisedPlan.researcherIndexes()) {
            if (isUnknownInFlight(session.branchStatus(index))) {
                continue;
            }
            session.branchExecution.put(index, "REPAIR_IN_FLIGHT_NON_IDEMPOTENT");
            checkpoint(session, state, TARGETED_REPAIR, "RUNNING", false);
            try {
                ResearchBranchResult result = branchExecutor.execute(session.request.context(), session.request.request(), revisedPlan, index);
                session.branchResults.put(index, result);
                session.branchExecution.put(index, "REPAIRED_SUCCEEDED");
            } catch (QuotaInsufficientException fatal) {
                session.branchExecution.put(index, "REPAIR_FAILED_QUOTA");
                throw fatal;
            } catch (Exception error) {
                session.branchResults.put(index, ResearchBranchResult.failure(index, revisedPlan.assignedSections(index),
                        System.currentTimeMillis(), error));
                session.branchExecution.put(index, "REPAIR_FAILED");
            }
        }
        List<ResearchBranchResult> branches = session.currentBranchResults();
        ResearchEvidenceMerger.MergeResult mergedEvidence = mergeEvidenceResult(branches);
        persistEvidenceLedger(session, mergedEvidence);
        List<ResearchEvidencePacket> evidence = mergedEvidence.evidence();
        ReportSpec reportSpec = ReportSpec.fromResearch(plan(state), evidence);
        Map<String, Object> update = Map.of(DeepResearchCheckpointState.BRANCH_RESULTS,
                branches.stream().map(ResearchBranchResult::toMap).toList(),
                DeepResearchCheckpointState.EVIDENCE, evidence.stream().map(ResearchEvidencePacket::toMap).toList(),
                DeepResearchCheckpointState.CLAIM_GRAPH, claimGraph(evidence),
                DeepResearchCheckpointState.RESEARCH_METRICS, researchMetrics(session, mergedEvidence),
                DeepResearchCheckpointState.REPORT_SPEC, reportSpec.toMap(),
                DeepResearchCheckpointState.MARKDOWN, reportSpec.reviewMarkdown(),
                DeepResearchCheckpointState.REPAIR_COUNT, repairCount + 1,
                DeepResearchCheckpointState.REPAIR_TARGETS, List.of(),
                "repairRequired", false,
                "phase", TARGETED_REPAIR);
        return checkpointed(session, state, update, TARGETED_REPAIR, "RUNNING", false);
    }

    private Map<String, Object> reportSpec(NativeSession session, Map<String, Object> state) throws Exception {
        prepareNode(session, REPORT_SPEC);
        String deliveryState = text(state.get(DeepResearchCheckpointState.DELIVERY_STATE));
        if ("IN_FLIGHT_NON_IDEMPOTENT".equals(deliveryState)) {
            Map<String, Object> update = Map.of(DeepResearchCheckpointState.DELIVERY_STATE, "MANUAL_RECONCILIATION_REQUIRED",
                    DeepResearchCheckpointState.TERMINAL_REASON, "report artifact delivery crash window requires reconciliation",
                    "phase", REPORT_SPEC, "completed", false,
                    "summary", "Deep Research report requires artifact delivery reconciliation");
            return checkpointed(session, state, update, REPORT_SPEC, "DEGRADED", true);
        }
        if ("SUCCEEDED".equals(deliveryState)) {
            return checkpointed(session, state, Map.of("phase", REPORT_SPEC), REPORT_SPEC, "SUCCEEDED", true);
        }

        List<ResearchBranchResult> branches = branchResults(session, state);
        List<ResearchEvidencePacket> evidence = evidence(state, branches);
        ReportSpec reportSpec = ReportSpec.from(state.get(DeepResearchCheckpointState.REPORT_SPEC));
        if (reportSpec.claims().isEmpty() && !evidence.isEmpty()) {
            reportSpec = ReportSpec.fromResearch(plan(state), evidence);
        }
        CitationGate.Result gate = citationGate.validate(reportSpec, evidence);
        String reviewMarkdown = reportSpec.reviewMarkdown();
        Map<String, Object> beforeDelivery = new LinkedHashMap<>(state);
        beforeDelivery.put(DeepResearchCheckpointState.REPORT_SPEC, reportSpec.toMap());
        beforeDelivery.put(DeepResearchCheckpointState.CITATION_GATE, gate.toMap());
        beforeDelivery.put(DeepResearchCheckpointState.MARKDOWN, reviewMarkdown);
        beforeDelivery.put(DeepResearchCheckpointState.DELIVERY_STATE, "IN_FLIGHT_NON_IDEMPOTENT");
        checkpoint(session, beforeDelivery, REPORT_SPEC, "RUNNING", false);

        List<ToolArtifactBinding> bindings = new ArrayList<>();
        if (artifactDelivery != null) {
            bindings.addAll(artifactDelivery.deliver(session.request.context(), session.request.request(), session.request.threadId(),
                    reportSpec, reviewMarkdown));
        }
        List<Map<String, Object>> refs = ToolArtifactFormatter.toArtifactRefs(bindings);
        String artifactId = refs.isEmpty()
                ? bindings.isEmpty() ? "" : ToolArtifactFormatter.buildArtifactKey(bindings.getFirst())
                : text(refs.getFirst().get("resourceKey"));
        ReportQualityResult quality = ReportQualityResult.from(state.get(DeepResearchCheckpointState.QUALITY));
        boolean trusted = quality.passed() && gate.passed();
        String summary = trusted ? "Deep Research report completed" : gate.passed()
                ? "Deep Research report completed with explicit uncertainty"
                : "Deep Research report rendered but Citation Gate rejected high-confidence completion";
        emitFinalReportEvent(session, quality, refs, artifactId, reviewMarkdown);
        Map<String, Object> update = Map.of(DeepResearchCheckpointState.DELIVERY_STATE, "SUCCEEDED",
                DeepResearchCheckpointState.REPORT_ARTIFACT_ID, artifactId,
                DeepResearchCheckpointState.ARTIFACT_REFS, refs,
                DeepResearchCheckpointState.REPORT_SPEC, reportSpec.toMap(),
                DeepResearchCheckpointState.CITATION_GATE, gate.toMap(),
                DeepResearchCheckpointState.MARKDOWN, reviewMarkdown,
                DeepResearchCheckpointState.TERMINAL_REASON, trusted ? "COMPLETED" : gate.passed() ? "DEGRADED_EVIDENCE" : "CITATION_GATE_FAILED",
                "completed", trusted, "summary", summary, "phase", REPORT_SPEC);
        return checkpointed(session, state, update, REPORT_SPEC, trusted ? "SUCCEEDED" : "DEGRADED", true);
    }

    private void emitFinalReportEvent(NativeSession session,
                                      ReportQualityResult quality,
                                      List<Map<String, Object>> artifactRefs,
                                      String artifactId,
                                      String markdown) {
        AgentContext context = session.request.context();
        if (context == null || context.getPrinter() == null || context.getPrinter().isAborted()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", REPORT_SPEC);
        payload.put("role", "report_artifact");
        payload.put("status", "completed");
        payload.put("progress", 100);
        payload.put("reportArtifactId", artifactId);
        payload.put("qualityStatus", quality.status());
        payload.put("citationCoverage", quality.citationCoverage());
        payload.put("sourceCount", quality.sourceCount());
        payload.put("charCount", quality.charCount());
        payload.put("checkpointThreadId", session.request.threadId());
        payload.put("previewMarkdown", StringUtils.abbreviate(StringUtils.defaultString(markdown), 1600));
        context.getPrinter().send(new AgentStreamEvent.StageOutput(
                context.getRequestId(), REPORT_SPEC, "deep_research_report", payload, artifactRefs, true));
    }

    private DeepResearchResult toResult(Map<String, Object> state, NativeSession session) {
        ResearchPlan plan = session.plan == null ? plan(state) : session.plan;
        List<ResearchBranchResult> branches = branchResults(session, state);
        List<ResearchEvidencePacket> evidence = evidence(state, branches);
        ReportQualityResult quality = ReportQualityResult.from(state.get(DeepResearchCheckpointState.QUALITY));
        String markdown = text(state.get(DeepResearchCheckpointState.MARKDOWN));
        if (StringUtils.isBlank(markdown)) {
            markdown = ReportSpec.fromResearch(plan, evidence).reviewMarkdown();
        }
        boolean delivered = "SUCCEEDED".equals(text(state.get(DeepResearchCheckpointState.DELIVERY_STATE)));
        boolean citationGateRejected = "CITATION_GATE_FAILED".equals(
                text(state.get(DeepResearchCheckpointState.TERMINAL_REASON)));
        boolean allResearchBranchesSucceeded = !session.branchExecution.isEmpty()
                && session.branchExecution.values().stream().allMatch(status ->
                "SUCCEEDED".equals(status) || "REPAIRED_SUCCEEDED".equals(status));
        return new DeepResearchResult(text(state.get("summary")), markdown,
                text(state.get(DeepResearchCheckpointState.REPORT_ARTIFACT_ID)), quality.status(),
                quality.citationCoverage(), evidence.size(), StringUtils.length(markdown),
                integer(state.get(DeepResearchCheckpointState.REPAIR_COUNT)), artifactRefs(state),
                delivered && !citationGateRejected && allResearchBranchesSucceeded);
    }

    private Map<String, Object> checkpointed(NativeSession session,
                                              Map<String, Object> state,
                                              Map<String, Object> update,
                                              String phase,
                                              String status,
                                              boolean terminal) {
        Map<String, Object> merged = merged(state, update);
        checkpoint(session, merged, phase, status, terminal);
        return update;
    }

    private void checkpoint(NativeSession session,
                            Map<String, Object> state,
                            String phase,
                            String status,
                            boolean terminal) {
        DeepResearchCheckpointState projection = DeepResearchCheckpointState.project(state, session.currentBranchResults(),
                session.branchExecution, phase, terminal ? text(state.get(DeepResearchCheckpointState.TERMINAL_REASON)) : "");
        checkpointPort.save(new GraphRunSnapshot(GRAPH_ID, session.request.threadId(), status, terminal, Instant.now(), projection.values()));
    }

    private void prepareNode(NativeSession session, String node) {
        ensureActive(session);
        AgentHarnessFacade harness = agentHarnessFacade;
        if (harness != null) {
            // Deterministic nodes still bind the same run and context projection as
            // model/tool nodes; no graph node constructs an AgentLoop directly.
            harness.bind(session.request.context());
            harness.projectContext(session.request.context());
        }
    }

    private void ensureActive(NativeSession session) {
        if (session.request.context() != null
                && session.request.context().cancellationReason() != AgentStopReason.NONE) {
            throw new CancellationException("deep research cancelled");
        }
    }

    private ResearchPlan plan(Map<String, Object> state) {
        return ResearchPlan.from(state.get(DeepResearchCheckpointState.PLAN), text(state.get(DeepResearchCheckpointState.QUERY)));
    }

    private List<ResearchBranchResult> branchResults(NativeSession session, Map<String, Object> state) {
        List<ResearchBranchResult> stored = branches(state);
        return stored.isEmpty() ? session.currentBranchResults() : stored;
    }

    private List<ResearchBranchResult> branches(Map<String, Object> state) {
        Object value = state.get(DeepResearchCheckpointState.BRANCH_RESULTS);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(ResearchBranchResult::from).filter(result -> !result.researcherId().isBlank()).toList();
    }

    private List<ResearchEvidencePacket> evidence(Map<String, Object> state, List<ResearchBranchResult> branches) {
        Object value = state.get(DeepResearchCheckpointState.EVIDENCE);
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(ResearchEvidencePacket::from).filter(ResearchEvidencePacket::isFinalReportEvidence).toList();
        }
        return mergeEvidence(branches);
    }

    private List<ResearchEvidencePacket> mergeEvidence(List<ResearchBranchResult> branches) {
        return mergeEvidenceResult(branches).evidence();
    }

    private ResearchEvidenceMerger.MergeResult mergeEvidenceResult(List<ResearchBranchResult> branches) {
        List<ResearchEvidencePacket> supplied = latestBySection(branches).values().stream()
                .flatMap(branch -> branch.evidence().stream()).toList();
        return evidenceMerger.merge(supplied);
    }

    private List<Map<String, Object>> claimGraph(List<ResearchEvidencePacket> evidence) {
        return evidence.stream().map(packet -> Map.<String, Object>of("claimId", packet.claimId(),
                "evidenceId", packet.evidenceId(), "sourceUrl", packet.url(), "sourceTitle", packet.title(),
                "relation", packet.relation(), "freshness", packet.freshness())).toList();
    }

    private void persistEvidenceLedger(NativeSession session, ResearchEvidenceMerger.MergeResult mergedEvidence) {
        ResearchEvidenceLedger ledger = evidenceLedger;
        if (ledger != null && !mergedEvidence.evidence().isEmpty()) {
            ledger.persist(ResearchEvidenceLedger.Batch.from(session.request.context(), session.request.threadId(), mergedEvidence));
        }
    }

    private Map<String, Object> researchMetrics(NativeSession session, ResearchEvidenceMerger.MergeResult mergedEvidence) {
        Map<String, Object> metrics = new LinkedHashMap<>(mergedEvidence.metrics());
        metrics.put("searchCostMicrocredits", session.request.context().getAgentRunState().getChargedMicrocreditsValue());
        metrics.put("offlineFixture", mergedEvidence.evidence().stream().anyMatch(ResearchEvidencePacket::offlineFixture));
        return Map.copyOf(metrics);
    }

    private String assembleMarkdown(ResearchPlan plan,
                                    List<ResearchBranchResult> branches,
                                    List<ResearchEvidencePacket> evidence) {
        StringBuilder markdown = new StringBuilder("# ").append(plan.title()).append("\n\n## 执行摘要\n\n")
                .append("结论仅覆盖可追溯到证据账本的范围；缺口、时效性风险和冲突不会被改写为确定性结论。\n\n")
                .append("## 研究计划\n\n");
        for (ResearchSubtask task : plan.subtasks()) {
            markdown.append("- ").append(task.id()).append("：").append(task.objective())
                    .append("；查询词=").append(String.join("、", task.queryTerms()))
                    .append("；最低证据=").append(task.minimumEvidence()).append('\n');
        }
        markdown.append('\n');
        Map<String, ResearchBranchResult> latest = latestBySection(branches);
        for (ResearchSubtask task : plan.subtasks()) {
            markdown.append("## ").append(task.section()).append("\n\n");
            ResearchBranchResult branch = latest.get(task.section());
            List<ResearchEvidencePacket> sectionEvidence = branch == null ? List.of()
                    : branch.evidence().stream().filter(ResearchEvidencePacket::isFinalReportEvidence).toList();
            if (sectionEvidence.size() < task.minimumEvidence()) {
                markdown.append("证据不足：此子任务未达到最低真实来源要求。\n\n");
                continue;
            }
            String prose = sanitizeBranchMarkdown(branch.markdown(), sectionEvidence);
            markdown.append(StringUtils.defaultIfBlank(prose, "已验证证据支持的结论需在重新生成时补充。"));
            markdown.append("\n\n已验证证据：");
            sectionEvidence.forEach(packet -> markdown.append(citation(evidence, packet)).append(' '));
            markdown.append("\n\n");
        }
        markdown.append("## 冲突与不确定性\n\n");
        List<String> conflicts = branches.stream().flatMap(branch -> branch.conflicts().stream())
                .filter(StringUtils::isNotBlank).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        conflictingClaimIds(evidence).forEach(claimId -> conflicts.add("claim=" + claimId + " 同时存在 SUPPORTS 与 CONTRADICTS 来源"));
        if (conflicts.isEmpty()) {
            markdown.append("- 未报告可定位冲突；这不等于不存在反例。\n\n");
        } else {
            conflicts.forEach(conflict -> markdown.append("- ").append(conflict).append('\n'));
            markdown.append("\n这些冲突限制结论为条件性判断。\n\n");
        }
        markdown.append("## 证据与来源\n\n");
        if (evidence.isEmpty()) {
            markdown.append("- 证据不足：没有满足 URL、标题、摘录和 claimId 契约的来源。\n");
        } else {
            for (int index = 0; index < evidence.size(); index++) {
                ResearchEvidencePacket packet = evidence.get(index);
                markdown.append("- [S").append(index + 1).append("] evidenceId=").append(packet.evidenceId())
                        .append("；claimId=").append(packet.claimId())
                        .append("；").append(packet.title()).append(" - ").append(packet.url())
                        .append("：").append(StringUtils.abbreviate(packet.snippet(), 480)).append('\n');
            }
        }
        return markdown.toString();
    }

    private ReportQualityResult reviewQuality(ResearchPlan plan,
                                              List<ResearchBranchResult> branches,
                                              List<ResearchEvidencePacket> evidence,
                                              String markdown,
                                              Map<Integer, String> branchExecution) {
        Map<String, ResearchBranchResult> latest = latestBySection(branches);
        List<String> failed = new ArrayList<>();
        for (ResearchSubtask task : plan.subtasks()) {
            ResearchBranchResult branch = latest.get(task.section());
            long sourceCount = branch == null ? 0L : branch.evidence().stream().filter(ResearchEvidencePacket::isFinalReportEvidence).count();
            if (sourceCount < task.minimumEvidence()) {
                failed.add(task.section());
            }
        }
        List<String> issues = new ArrayList<>();
        if (evidence.isEmpty()) {
            issues.add("没有可引用来源");
        }
        CitationReview citations = citationReview(markdown, evidence.size());
        if (!citations.invalid().isEmpty()) {
            issues.add("引用编号不匹配=" + citations.invalid());
        }
        if (!evidence.isEmpty() && citations.coverage() < 1D) {
            issues.add("引用覆盖不足=" + citations.coverage());
        }
        for (ResearchEvidencePacket packet : evidence) {
            if (isStale(packet)) {
                issues.add("时效性不足，需复查 claim=" + packet.claimId());
            }
        }
        conflictingClaimIds(evidence).forEach(claimId -> issues.add("事实冲突=claim " + claimId));
        branches.stream().flatMap(branch -> branch.conflicts().stream()).filter(StringUtils::isNotBlank)
                .distinct().forEach(conflict -> issues.add("事实冲突=" + conflict));
        branchExecution.forEach((index, status) -> {
            if ("MANUAL_RECONCILIATION_REQUIRED".equals(status) || isUnknownInFlight(status)) {
                issues.add("分支 " + index + " 处于非幂等 crash-window，需人工核对");
            }
        });
        if (failed.isEmpty() && issues.isEmpty()) {
            return ReportQualityResult.passed(citations.coverage(), evidence.size(), StringUtils.length(markdown));
        }
        return ReportQualityResult.failed(List.copyOf(failed), List.copyOf(issues), citations.coverage(), evidence.size(),
                StringUtils.length(markdown));
    }

    private ResearchPlanRevision revisionFor(ResearchPlan plan,
                                             List<ResearchBranchResult> branches,
                                             ReportQualityResult quality,
                                             Map<Integer, String> branchExecution) {
        LinkedHashSet<String> targets = new LinkedHashSet<>(plan.subtaskIdsForSections(quality.failedSections()));
        Map<String, ResearchBranchResult> latest = latestBySection(branches);
        for (ResearchSubtask task : plan.subtasks()) {
            ResearchBranchResult branch = latest.get(task.section());
            if (branch != null && (!branch.conflicts().isEmpty()
                    || branch.evidence().stream().anyMatch(this::isStale))) {
                targets.add(task.id());
            }
        }
        for (Integer index : plan.researcherIndexes()) {
            if (isUnknownInFlight(branchExecution.get(index))) {
                for (ResearchSubtask task : plan.assignedSubtasks(index)) {
                    targets.remove(task.id());
                }
            }
        }
        return new ResearchPlanRevision(List.copyOf(targets), List.copyOf(quality.issues()));
    }

    private CitationReview citationReview(String markdown, int sourceCount) {
        String body = StringUtils.substringBefore(StringUtils.defaultString(markdown), "## 证据与来源");
        LinkedHashSet<Integer> valid = new LinkedHashSet<>();
        List<Integer> invalid = new ArrayList<>();
        Matcher matcher = CITATION.matcher(body);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index > 0 && index <= sourceCount) {
                valid.add(index);
            } else {
                invalid.add(index);
            }
        }
        return new CitationReview(sourceCount == 0 ? 0D : (double) valid.size() / sourceCount, List.copyOf(invalid));
    }

    private boolean isStale(ResearchEvidencePacket packet) {
        if ("STALE".equalsIgnoreCase(packet.freshness())) {
            return true;
        }
        String text = StringUtils.lowerCase(packet.snippet());
        if (StringUtils.containsAny(text, "stale", "outdated", "已过期", "过时")) {
            return true;
        }
        Matcher matcher = YEAR.matcher(text);
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group());
            if (year <= Year.now().getValue() - 5) {
                return true;
            }
        }
        return false;
    }

    private List<String> conflictingClaimIds(List<ResearchEvidencePacket> evidence) {
        Map<String, LinkedHashSet<String>> relations = new LinkedHashMap<>();
        for (ResearchEvidencePacket packet : evidence) {
            relations.computeIfAbsent(packet.claimId(), ignored -> new LinkedHashSet<>())
                    .add(StringUtils.upperCase(packet.relation()));
        }
        return relations.entrySet().stream()
                .filter(entry -> entry.getValue().contains("SUPPORTS") && entry.getValue().contains("CONTRADICTS"))
                .map(Map.Entry::getKey).toList();
    }

    private Map<String, ResearchBranchResult> latestBySection(List<ResearchBranchResult> results) {
        Map<String, ResearchBranchResult> latest = new LinkedHashMap<>();
        for (ResearchBranchResult result : results) {
            for (String section : result.assignedSections()) {
                ResearchBranchResult existing = latest.get(section);
                if (existing == null || result.completedAtMillis() >= existing.completedAtMillis()) {
                    latest.put(section, result);
                }
            }
        }
        return latest;
    }

    private String sanitizeBranchMarkdown(String branchMarkdown, List<ResearchEvidencePacket> allowedEvidence) {
        if (StringUtils.isBlank(branchMarkdown)) {
            return "";
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> artifactRefs(Map<String, Object> state) {
        Object refs = state.get(DeepResearchCheckpointState.ARTIFACT_REFS);
        return refs instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private Map<String, Object> merged(Map<String, Object> state, Map<String, Object> update) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (state != null) {
            merged.putAll(state);
        }
        if (update != null) {
            merged.putAll(update);
        }
        return merged;
    }

    private String branchKey(int index) {
        return "branch_" + index;
    }

    private String researcherNode(int index) {
        return "researcher_" + index;
    }

    private int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isCompletedBranchStatus(String status) {
        return "SUCCEEDED".equals(status) || "REPAIRED_SUCCEEDED".equals(status) || "FAILED".equals(status);
    }

    private boolean isUnknownInFlight(String status) {
        return status != null && (status.contains("IN_FLIGHT_NON_IDEMPOTENT")
                || "MANUAL_RECONCILIATION_REQUIRED".equals(status));
    }

    private record CitationReview(double coverage, List<Integer> invalid) {
    }

    private static final class NativeSession {
        private final GraphRunRequest request;
        private final ConcurrentMap<Integer, ResearchBranchResult> branchResults = new ConcurrentHashMap<>();
        private final ConcurrentMap<Integer, String> branchExecution = new ConcurrentHashMap<>();
        private volatile ResearchPlan plan;
        private volatile boolean repairInProgress;

        private NativeSession(GraphRunRequest request, Map<String, Object> checkpointState) {
            this.request = request;
            if (checkpointState != null) {
                ResearchPlan restored = ResearchPlan.from(checkpointState.get(DeepResearchCheckpointState.PLAN),
                        checkpointState.get(DeepResearchCheckpointState.QUERY) == null ? "" :
                                String.valueOf(checkpointState.get(DeepResearchCheckpointState.QUERY)));
                if (checkpointState.containsKey(DeepResearchCheckpointState.PLAN)) {
                    this.plan = restored;
                }
                Object storedBranches = checkpointState.get(DeepResearchCheckpointState.BRANCH_RESULTS);
                if (storedBranches instanceof List<?> values) {
                    values.stream().map(ResearchBranchResult::from).filter(result -> !result.researcherId().isBlank())
                            .forEach(result -> branchResults.put(researcherIndex(result.researcherId()), result));
                }
                Object statuses = checkpointState.get(DeepResearchCheckpointState.BRANCH_EXECUTION);
                if (statuses instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> branchExecution.put(integer(key), String.valueOf(value)));
                }
            }
        }

        private List<ResearchBranchResult> currentBranchResults() {
            return branchResults.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
        }

        private String branchStatus(int index) {
            return branchExecution.getOrDefault(index, "");
        }

        private static int researcherIndex(String researcherId) {
            if (researcherId == null) {
                return 0;
            }
            int separator = researcherId.lastIndexOf('_');
            try {
                return separator < 0 ? 0 : Integer.parseInt(researcherId.substring(separator + 1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private static int integer(Object value) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }
}
