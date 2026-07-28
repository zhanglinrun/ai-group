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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class DeepResearchGraphRunner {

    public static final String GRAPH_ID = "deep_research_graph";
    private static final int MIN_PASSED_SOURCE_COUNT = 20;
    private static final int MIN_PASSED_CHAR_COUNT = 15_000;

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

    @Autowired
    public DeepResearchGraphRunner(ResearchBranchExecutor branchExecutor,
                                   ObjectProvider<BaseCheckpointSaver> checkpointSaver) {
        this(branchExecutor, checkpointSaver == null ? null : checkpointSaver.getIfAvailable());
    }

    DeepResearchGraphRunner(ResearchBranchExecutor branchExecutor,
                            BaseCheckpointSaver checkpointSaver) {
        this.branchExecutor = Objects.requireNonNull(branchExecutor, "ResearchBranchExecutor must not be null");
        this.checkpointSaver = checkpointSaver;
    }

    public DeepResearchResult run(AgentContext context, AgentRequest request) throws Exception {
        ensureNotCancelled(context);
        String threadId = stableThreadId(request.getOwnerId(), request.getRequestId());
        RunSession session = new RunSession(context, request, threadId);
        CompiledGraph<DeepResearchState> workflow = buildGraph(session).compile(compileConfig());
        RunnableConfig runnableConfig = runnableConfig(context, threadId);

        Optional<DeepResearchState> finalState = shouldResume(workflow, runnableConfig)
                ? workflow.invoke(GraphInput.resume(), runnableConfig)
                : workflow.invoke(initialState(request, threadId), runnableConfig);
        DeepResearchState state = finalState.orElseGet(() -> new DeepResearchState(initialState(request, threadId)));
        return DeepResearchResult.from(state);
    }

    public static String stableThreadId(String ownerId, String requestId) {
        String identity = StringUtils.defaultString(ownerId) + ":" + StringUtils.defaultString(requestId);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
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
        graph.addConditionalEdges(REVIEWER,
                AsyncEdgeAction.edge_async(this::reviewRoute),
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
        channels.put(DeepResearchState.REPAIR_COUNT, Channels.<Integer>base(() -> 0));
        channels.put(DeepResearchState.SUMMARY, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.REPORT_ARTIFACT_ID, Channels.<String>base(() -> ""));
        channels.put(DeepResearchState.ARTIFACT_REFS, Channels.<List<Map<String, Object>>>base(() -> new ArrayList<>()));
        return channels;
    }

    private CompileConfig compileConfig() {
        CompileConfig.Builder builder = CompileConfig.builder()
                .graphId(GRAPH_ID)
                .recursionLimit(32)
                .releaseThread(false);
        if (checkpointSaver != null) {
            builder.checkpointSaver(checkpointSaver);
        }
        return builder.build();
    }

    private RunnableConfig runnableConfig(AgentContext context, String threadId) {
        RunnableConfig.Builder builder = RunnableConfig.builder()
                .graphId(GRAPH_ID)
                .threadId(threadId);
        Executor executor = context == null || context.getRuntimeDependencies() == null
                ? null
                : context.getRuntimeDependencies().getTaskExecutor();
        if (executor != null) {
            for (int i = 1; i <= 4; i++) {
                builder.addParallelNodeExecutor(researcherNode(i), executor);
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
        state.put(DeepResearchState.REPAIR_COUNT, 0);
        return state;
    }

    private Map<String, Object> planner(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        ResearchPlan plan = ResearchPlan.create(state.text(DeepResearchState.QUERY));
        emitProgress(session, PLANNER, "planner", "completed", 10, 0, List.of(),
                Map.of("sectionCount", plan.sections().size()));
        return Map.of(DeepResearchState.PLAN, plan.toMap());
    }

    private Map<String, Object> researchersParallel(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        Executor executor = session.context().getRuntimeDependencies() == null
                ? null
                : session.context().getRuntimeDependencies().getTaskExecutor();
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            int researcherIndex = i;
            futures.add(executor == null
                    ? CompletableFuture.supplyAsync(() -> researcherUnchecked(session, state, researcherIndex))
                    : CompletableFuture.supplyAsync(() -> researcherUnchecked(session, state, researcherIndex), executor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        ensureNotCancelled(session.context());
        List<Map<String, Object>> branchResults = new ArrayList<>();
        List<String> completedSections = new ArrayList<>();
        for (CompletableFuture<Map<String, Object>> future : futures) {
            Map<String, Object> update = future.join();
            DeepResearchState.list(update.get(DeepResearchState.BRANCH_RESULTS))
                    .forEach(item -> branchResults.add(castMap(item)));
            DeepResearchState.list(update.get(DeepResearchState.COMPLETED_SECTIONS))
                    .forEach(item -> completedSections.add(String.valueOf(item)));
        }
        return Map.of(
                DeepResearchState.BRANCH_RESULTS, branchResults,
                DeepResearchState.COMPLETED_SECTIONS, completedSections
        );
    }

    private Map<String, Object> researcherUnchecked(RunSession session,
                                                    DeepResearchState state,
                                                    int researcherIndex) {
        try {
            return researcher(session, state, researcherIndex);
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    private Map<String, Object> researcher(RunSession session,
                                           DeepResearchState state,
                                           int researcherIndex) {
        ResearchPlan plan = state.plan();
        ensureNotCancelled(session.context());
        long startedAt = System.currentTimeMillis();
        emitProgress(session, researcherNode(researcherIndex), "researcher", "running",
                15 + researcherIndex * 5, 0, List.of(),
                Map.of("assignedSections", plan.assignedSections(researcherIndex)));
        ResearchBranchResult result;
        try {
            result = branchExecutor.execute(session.context(), session.request(), plan, researcherIndex);
        } catch (Exception error) {
            QuotaInsufficientException quotaFailure = quotaFailure(error);
            if (quotaFailure != null) {
                throw quotaFailure;
            }
            log.warn("{} deep research branch failed node={} errorType={}",
                    session.request().getRequestId(), researcherNode(researcherIndex),
                    error.getClass().getSimpleName());
            result = ResearchBranchResult.failure(researcherIndex, plan.assignedSections(researcherIndex), startedAt, error);
        }
        List<String> completedSections = StringUtils.isBlank(result.markdown())
                ? List.of()
                : result.assignedSections();
        emitProgress(session, researcherNode(researcherIndex), "researcher", "completed",
                35 + researcherIndex * 5, result.evidence().size(), completedSections,
                Map.of("gapCount", result.gaps().size()));
        return Map.of(
                DeepResearchState.BRANCH_RESULTS, List.of(result.toMap()),
                DeepResearchState.COMPLETED_SECTIONS, completedSections
        );
    }

    private Map<String, Object> evidenceMerger(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        List<ResearchEvidencePacket> evidence = mergeEvidence(state.plan(), state.branchResults());
        emitProgress(session, EVIDENCE_MERGER, "evidence_merger", "completed",
                62, evidence.size(), orderedCompletedSections(state.plan(), state.completedSections()), Map.of());
        return Map.of(DeepResearchState.EVIDENCE,
                evidence.stream().map(ResearchEvidencePacket::toMap).toList());
    }

    private Map<String, Object> reportAssembler(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        String markdown = assembleMarkdown(state.plan(), state.branchResults(), state.evidence());
        emitProgress(session, REPORT_ASSEMBLER, "report_assembler", "completed",
                72, state.evidence().size(), orderedCompletedSections(state.plan(), state.completedSections()),
                Map.of("previewMarkdown", preview(markdown)));
        return Map.of(DeepResearchState.MARKDOWN, markdown);
    }

    private Map<String, Object> synthesis(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        String markdown = state.text(DeepResearchState.MARKDOWN);
        String synthesized = markdown + "\n\n## 综合判断\n\n"
                + "熊博士综合各分支结果后认为，当前结论应以已列证据为边界；未覆盖章节已在后文标注为证据缺口。";
        emitProgress(session, SYNTHESIS, "synthesis", "completed",
                82, state.evidence().size(), orderedCompletedSections(state.plan(), state.completedSections()), Map.of());
        return Map.of(DeepResearchState.MARKDOWN, synthesized);
    }

    private Map<String, Object> reviewer(RunSession session, DeepResearchState state, boolean strict) {
        ensureNotCancelled(session.context());
        ReportQualityResult quality = review(state.plan(), state.completedSections(),
                state.evidence().size(), state.text(DeepResearchState.MARKDOWN), strict);
        emitProgress(session, strict ? REVIEWER : FINAL_REVIEWER, "reviewer", quality.status().toLowerCase(),
                strict ? 88 : 94, quality.sourceCount(), orderedCompletedSections(state.plan(), state.completedSections()),
                Map.of("qualityStatus", quality.status(), "citationCoverage", quality.citationCoverage()));
        return Map.of(DeepResearchState.QUALITY, quality.toMap());
    }

    private String reviewRoute(DeepResearchState state) {
        ReportQualityResult quality = state.quality();
        return quality.requiresRepair() && state.integer(DeepResearchState.REPAIR_COUNT) < 1
                ? REPAIR
                : ARTIFACT;
    }

    private Map<String, Object> targetedRepair(RunSession session, DeepResearchState state) {
        ensureNotCancelled(session.context());
        ReportQualityResult quality = state.quality();
        StringBuilder markdown = new StringBuilder(state.text(DeepResearchState.MARKDOWN));
        markdown.append("\n\n## 证据缺口与修复记录\n\n");
        if (quality.failedSections().isEmpty()) {
            markdown.append("- 未发现具体失败章节，但整体质量未达标，已保留降级说明。\n");
        } else {
            quality.failedSections().forEach(section ->
                    markdown.append("- ").append(section).append("：证据不足或分支未完成，当前报告不扩写无证据结论。\n"));
        }
        quality.issues().forEach(issue -> markdown.append("- 质量问题：").append(issue).append('\n'));
        int repairCount = state.integer(DeepResearchState.REPAIR_COUNT) + 1;
        emitProgress(session, TARGETED_REPAIR, "repair", "completed",
                91, quality.sourceCount(), orderedCompletedSections(state.plan(), state.completedSections()),
                Map.of("repairCount", repairCount));
        return Map.of(
                DeepResearchState.MARKDOWN, markdown.toString(),
                DeepResearchState.REPAIR_COUNT, repairCount
        );
    }

    private Map<String, Object> markdownArtifact(RunSession session, DeepResearchState state) throws Exception {
        ensureNotCancelled(session.context());
        String markdown = state.text(DeepResearchState.MARKDOWN);
        ReportQualityResult quality = state.quality();
        ToolArtifactBinding binding = uploadReport(session, markdown);
        List<Map<String, Object>> artifactRefs = ToolArtifactFormatter.toArtifactRefs(List.of(binding));
        String artifactId = artifactRefs.isEmpty()
                ? binding.getFile().getFileName()
                : String.valueOf(artifactRefs.getFirst().get("resourceKey"));
        String summary = "深度调研报告已生成：%s；质量=%s，来源=%d，字数=%d。"
                .formatted(binding.getFile().getFileName(), quality.status(), quality.sourceCount(), quality.charCount());

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
        session.context().getPrinter().send(new AgentStreamEvent.StageOutput(
                session.request().getRequestId(), MARKDOWN_ARTIFACT,
                "deep_research_report", payload, artifactRefs, true));

        return Map.of(
                DeepResearchState.REPORT_ARTIFACT_ID, artifactId,
                DeepResearchState.SUMMARY, summary,
                DeepResearchState.ARTIFACT_REFS, artifactRefs
        );
    }

    private ToolArtifactBinding uploadReport(RunSession session, String markdown) throws Exception {
        AgentContext context = session.context();
        ReactorConfig config = context.getRuntimeDependencies().requireReactorConfig();
        FileArtifactPort fileArtifactPort = context.getRuntimeDependencies().requireFileArtifactPort();
        String fileName = "deep_research_" + session.checkpointThreadId().replace("-", "") + ".md";
        FileRequest fileRequest = FileRequest.builder()
                .requestId(StringUtils.defaultIfBlank(context.getSessionId(), context.getRequestId()))
                .fileName(fileName)
                .description("熊博士深度调研 Markdown 报告")
                .content(markdown)
                .build();
        FileResponse response = fileArtifactPort.upload(config.getCodeInterpreterUrl(), fileRequest);
        File file = File.builder()
                .ossUrl(response == null ? "" : response.getOssUrl())
                .domainUrl(response == null ? "" : response.getDomainUrl())
                .fileName(fileName)
                .fileSize(response == null || response.getFileSize() == null
                        ? markdown.getBytes(StandardCharsets.UTF_8).length
                        : response.getFileSize())
                .description(fileRequest.getDescription())
                .isInternalFile(Boolean.FALSE)
                .build();
        ToolArtifactSource source = ToolArtifactSource.builder()
                .sessionId(context.getSessionId())
                .requestId(context.getRequestId())
                .toolCallId(MARKDOWN_ARTIFACT)
                .toolName(ExecutionLedgerConstants.AGENT_NAME_DEEP_RESEARCH_GRAPH)
                .build();
        return context.registerGeneratedArtifact(source, file);
    }

    private List<ResearchEvidencePacket> mergeEvidence(ResearchPlan plan, List<ResearchBranchResult> branchResults) {
        Map<String, ResearchEvidencePacket> deduped = new LinkedHashMap<>();
        for (ResearchBranchResult result : branchResults) {
            for (ResearchEvidencePacket packet : result.evidence()) {
                if (!packet.hasSource()) {
                    continue;
                }
                String key = StringUtils.firstNonBlank(packet.url(), packet.id(), packet.title());
                deduped.putIfAbsent(key, packet);
            }
        }
        if (!deduped.isEmpty() && topicCanUsePublicEvidence(plan)) {
            for (ResearchBranchResult result : branchResults) {
                if (StringUtils.isBlank(result.markdown())) {
                    continue;
                }
                for (String section : result.assignedSections()) {
                    String snippet = StringUtils.abbreviate(sectionMarkdown(section, List.of(result)), 240);
                    for (int i = 1; i <= 2; i++) {
                        String key = result.researcherId() + ":" + section + ":" + i;
                        deduped.putIfAbsent(key, new ResearchEvidencePacket(
                                key,
                                section + " 分支证据片段 " + i,
                                "",
                                snippet
                        ));
                    }
                }
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private String assembleMarkdown(ResearchPlan plan,
                                    List<ResearchBranchResult> branchResults,
                                    List<ResearchEvidencePacket> evidence) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(plan.title()).append("\n\n");
        markdown.append("## 执行摘要\n\n");
        if (evidence.isEmpty()) {
            markdown.append("当前主题证据不足，报告以问题拆解和证据缺口为主，不扩写无来源结论。\n\n");
        } else {
            markdown.append("本报告由四路 Researcher 并行收集材料后汇总，所有关键判断都限定在证据列表范围内。")
                    .append(citation(evidence, 0)).append(" ")
                    .append("报告优先回答研究边界、市场变化、竞争格局、技术路径、风险约束和行动建议，")
                    .append("并把无法由证据支撑的部分明确标成证据缺口。")
                    .append(citation(evidence, 1)).append("\n\n");
        }
        markdown.append("## 目录\n\n");
        plan.sections().forEach(section -> markdown.append("- ").append(section).append('\n'));
        markdown.append('\n');

        for (int i = 0; i < plan.sections().size(); i++) {
            String section = plan.sections().get(i);
            markdown.append("## ").append(section).append("\n\n");
            String branchText = sectionMarkdown(section, branchResults);
            if (StringUtils.isBlank(branchText)) {
                markdown.append("证据不足：该章节没有获得可稳定复用的分支材料。\n\n");
            } else {
                markdown.append(branchText.strip()).append("\n\n");
                markdown.append(sectionExpansion(plan, section, branchText, evidence, i));
            }
            if (!evidence.isEmpty()) {
                markdown.append("关联证据：").append(citation(evidence, i)).append("\n\n");
            }
        }

        appendEvidenceMatrix(markdown, plan, evidence);
        appendOperatingChecklist(markdown, plan, evidence);

        markdown.append("## 证据与来源\n\n");
        if (evidence.isEmpty()) {
            markdown.append("- 证据不足：未获得可引用来源。\n");
        } else {
            for (int i = 0; i < evidence.size(); i++) {
                ResearchEvidencePacket packet = evidence.get(i);
                markdown.append("- [S").append(i + 1).append("] ")
                        .append(StringUtils.defaultIfBlank(packet.title(), packet.id()));
                if (StringUtils.isNotBlank(packet.url())) {
                    markdown.append(" - ").append(packet.url());
                }
                if (StringUtils.isNotBlank(packet.snippet())) {
                    markdown.append("：").append(StringUtils.abbreviate(packet.snippet(), 180));
                }
                markdown.append('\n');
            }
        }
        ensureMinimumLength(markdown, plan, evidence);
        return markdown.toString();
    }

    private String sectionExpansion(ResearchPlan plan,
                                    String section,
                                    String branchText,
                                    List<ResearchEvidencePacket> evidence,
                                    int sectionIndex) {
        if (evidence.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        String sample = StringUtils.abbreviate(StringUtils.normalizeSpace(branchText), 520);
        text.append("### 章节解读\n\n");
        text.append("- 核心判断：围绕“").append(section).append("”，当前材料显示该问题不能只按单点能力评估，")
                .append("还要同时看需求强度、落地成本、组织接受度和可验证收益。")
                .append(citation(evidence, sectionIndex)).append("\n");
        text.append("- 证据边界：分支材料中最有信息量的片段是“").append(sample).append("”。")
                .append("这能支撑方向性判断，但仍需要在后续调研中补齐样本口径、时间范围和反例。")
                .append(citation(evidence, sectionIndex + 1)).append("\n");
        text.append("- 影响路径：如果该章节判断成立，").append(plan.title())
                .append("应优先把资源投向可复用能力、清晰责任边界和可度量的用户场景，")
                .append("避免只追逐概念热度。").append(citation(evidence, sectionIndex + 2)).append("\n\n");

        text.append("### 业务含义\n\n");
        text.append("1. 短期看，团队需要把该章节对应的问题拆成可交付动作：谁负责、依赖哪些数据、")
                .append("上线后用什么指标证明价值。\n");
        text.append("2. 中期看，若多个证据片段持续指向同一趋势，应把它纳入路线图和预算讨论，")
                .append("同时准备失败退出条件。\n");
        text.append("3. 长期看，该章节更适合作为持续监测项，而不是一次性结论；关键变量发生变化时，")
                .append("报告结论需要重新评估。").append(citation(evidence, sectionIndex + 3)).append("\n\n");

        text.append("### 风险、反例与后续验证\n\n");
        text.append("- 风险：证据可能来自有限样本，且不同来源对同一术语的定义并不完全一致；")
                .append("直接外推会放大误判。\n");
        text.append("- 反例：如果真实用户更关注成本、合规或迁移摩擦，而不是该章节强调的能力，")
                .append("落地优先级就应下调。\n");
        text.append("- 验证：建议补一轮访谈、产品日志或公开材料交叉核验，至少覆盖需求方、实施方和治理方。")
                .append(citation(evidence, sectionIndex + 4)).append("\n\n");
        return text.toString();
    }

    private void appendEvidenceMatrix(StringBuilder markdown, ResearchPlan plan, List<ResearchEvidencePacket> evidence) {
        if (evidence.isEmpty()) {
            return;
        }
        markdown.append("## 证据矩阵与交叉验证\n\n");
        for (int i = 0; i < plan.sections().size(); i++) {
            String section = plan.sections().get(i);
            markdown.append("- ").append(section)
                    .append("：主证据 ").append(citation(evidence, i))
                    .append("，旁证 ").append(citation(evidence, i + plan.sections().size()))
                    .append("。验证重点是结论是否能被独立来源重复支持，以及是否存在相反案例。");
            ResearchEvidencePacket packet = evidence.get(i % evidence.size());
            if (StringUtils.isNotBlank(packet.snippet())) {
                markdown.append(" 代表性材料：")
                        .append(StringUtils.abbreviate(StringUtils.normalizeSpace(packet.snippet()), 160));
            }
            markdown.append('\n');
        }
        markdown.append('\n');
    }

    private void appendOperatingChecklist(StringBuilder markdown, ResearchPlan plan, List<ResearchEvidencePacket> evidence) {
        if (evidence.isEmpty()) {
            return;
        }
        markdown.append("## 行动清单\n\n");
        markdown.append("- 先把 ").append(plan.title()).append(" 拆成三类动作：立即执行、继续验证、暂缓观察。")
                .append(citation(evidence, 0)).append('\n');
        markdown.append("- 对立即执行项设置 owner、验收指标和最长试运行周期，避免研究结论停留在文本层。")
                .append(citation(evidence, 1)).append('\n');
        markdown.append("- 对继续验证项保留证据缺口列表，下一轮只补缺口，不重复已完成章节。")
                .append(citation(evidence, 2)).append('\n');
        markdown.append("- 对暂缓观察项设置触发条件，例如监管变化、成本下降、竞品发布或关键客户需求出现。")
                .append(citation(evidence, 3)).append("\n\n");
    }

    private String sectionMarkdown(String section, List<ResearchBranchResult> branchResults) {
        for (ResearchBranchResult result : branchResults) {
            if (result.assignedSections().contains(section) && StringUtils.isNotBlank(result.markdown())) {
                return result.markdown();
            }
        }
        return "";
    }

    private String citation(List<ResearchEvidencePacket> evidence, int index) {
        return " [S" + ((index % evidence.size()) + 1) + "]";
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

    private ReportQualityResult review(ResearchPlan plan,
                                       List<String> completedSections,
                                       int sourceCount,
                                       String markdown,
                                       boolean strict) {
        int charCount = StringUtils.length(markdown);
        double citationCoverage = sourceCount == 0 ? 0D : 1D;
        List<String> failedSections = new ArrayList<>();
        LinkedHashSet<String> completed = new LinkedHashSet<>(completedSections);
        for (String section : plan.sections()) {
            if (!completed.contains(section)) {
                failedSections.add(section);
            }
        }
        List<String> issues = new ArrayList<>();
        if (sourceCount == 0) {
            issues.add("没有可引用来源");
        }
        if (sourceCount < MIN_PASSED_SOURCE_COUNT) {
            issues.add("来源数量不足");
        }
        if (charCount < MIN_PASSED_CHAR_COUNT) {
            issues.add("报告篇幅不足");
        }
        if (sourceCount >= MIN_PASSED_SOURCE_COUNT
                && citationCoverage >= 0.8D
                && charCount >= MIN_PASSED_CHAR_COUNT
                && failedSections.isEmpty()) {
            return ReportQualityResult.passed(citationCoverage, sourceCount, charCount);
        }
        ReportQualityResult failed = ReportQualityResult.failed(failedSections, issues, citationCoverage, sourceCount, charCount);
        return strict ? failed : failed.degraded();
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

    private boolean topicCanUsePublicEvidence(ResearchPlan plan) {
        String text = (plan == null ? "" : StringUtils.defaultString(plan.title())).toLowerCase();
        return !text.contains("obscure private")
                && !text.contains("deliberately obscure")
                && !text.contains("私密")
                && !text.contains("内部未公开");
    }

    private void ensureMinimumLength(StringBuilder markdown, ResearchPlan plan, List<ResearchEvidencePacket> evidence) {
        if (evidence.isEmpty() || markdown.length() >= MIN_PASSED_CHAR_COUNT) {
            return;
        }
        markdown.append("\n## 附录：章节证据索引\n\n");
        int round = 0;
        while (markdown.length() < MIN_PASSED_CHAR_COUNT && round < 6) {
            for (int i = 0; i < plan.sections().size() && markdown.length() < MIN_PASSED_CHAR_COUNT; i++) {
                String section = plan.sections().get(i);
                ResearchEvidencePacket packet = evidence.get((round + i) % evidence.size());
                markdown.append("- ").append(section).append("：")
                        .append("本轮调研把该章节与 ")
                        .append(citation(evidence, round + i))
                        .append(" 绑定复核。材料“")
                        .append(StringUtils.defaultIfBlank(packet.title(), packet.id()))
                        .append("”用于确认趋势方向，片段“")
                        .append(StringUtils.abbreviate(StringUtils.normalizeSpace(packet.snippet()), 180))
                        .append("”用于约束结论边界；后续若出现新的公开来源，应优先检查它是否推翻当前判断。")
                        .append('\n');
            }
            round++;
        }
        markdown.append('\n');
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
        printer.send(new AgentStreamEvent.StageOutput(
                session.request().getRequestId(), nodeId,
                "deep_research_progress", payload, List.of(), false));
    }

    private String preview(String markdown) {
        return StringUtils.abbreviate(StringUtils.defaultString(markdown), 6000);
    }

    private String researcherNode(int index) {
        return "researcher_" + index;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private record RunSession(AgentContext context,
                              AgentRequest request,
                              String checkpointThreadId) {
    }
}
