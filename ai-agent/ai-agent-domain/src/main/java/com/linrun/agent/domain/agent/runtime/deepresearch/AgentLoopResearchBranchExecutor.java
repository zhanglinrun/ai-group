package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.agent.ExplicitToolChoicePolicy;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.completion.ToolExecutionEvidence;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.AgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.harness.DefaultAgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.harness.ToolSideEffect;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolExecutionOutcome;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.DeepSearchDoc;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.DeepSearchQueryResult;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.DeepSearchStage;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.DeepSearchToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ExtractedEvidenceToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.FetchedPageToolOutput;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class AgentLoopResearchBranchExecutor implements ResearchBranchExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FETCHED_CANDIDATES_PER_BRANCH = 1;

    private final AgentToolCollectionFactory toolCollectionFactory;
    private final AgentLoopFactory agentLoopFactory;
    private final AgentHarnessFacade agentHarnessFacade;

    public AgentLoopResearchBranchExecutor(AgentToolCollectionFactory toolCollectionFactory,
                                           AgentLoopFactory agentLoopFactory) {
        this(toolCollectionFactory, agentLoopFactory, new DefaultAgentHarnessFacade(agentLoopFactory));
    }

    @Autowired
    public AgentLoopResearchBranchExecutor(AgentToolCollectionFactory toolCollectionFactory,
                                           AgentLoopFactory agentLoopFactory,
                                           AgentHarnessFacade agentHarnessFacade) {
        this.toolCollectionFactory = toolCollectionFactory;
        this.agentLoopFactory = agentLoopFactory;
        this.agentHarnessFacade = agentHarnessFacade;
    }

    @Override
    public ResearchBranchResult execute(AgentContext parentContext,
                                        AgentRequest parentRequest,
                                        ResearchPlan plan,
                                        int researcherIndex) {
        long startedAt = System.currentTimeMillis();
        List<String> assignedSections = plan.assignedSections(researcherIndex);
        String researcherId = "researcher_" + researcherIndex;
        List<ResearchSubtask> assignedSubtasks = plan.assignedSubtasks(researcherIndex);
        String originalQuery = StringUtils.defaultIfBlank(parentContext.getQuery(), parentRequest.getQuery());
        AgentContext childContext = parentContext.forkForParallelTask(researcherId);
        childContext.setExecutionProfile(AgentExecutionProfile.STANDARD);
        childContext.setOutputStyle("markdown");
        childContext.setPrinter(new BranchPrinter(parentContext.getPrinter()));

        ToolCollection catalog = toolCollectionFactory.buildForUnified(childContext, parentRequest);
        String explicitlyRequestedMcpTool = explicitlyRequestedMcpTool(catalog, originalQuery);
        log.info("{} deep research explicit MCP resolution catalogMcpCount={} selected={}",
                childContext.getRequestId(), catalog.getMcpToolMap().size(), explicitlyRequestedMcpTool);
        ToolCollection toolCollection = restrictToSubtaskTools(
                catalog, assignedSubtasks, explicitlyRequestedMcpTool);
        String requiredEvidenceTool = requiredNetworkEvidenceTool(toolCollection, assignedSubtasks);
        ToolCall explicitMcpPreflight = explicitlyRequestedMcpPreflight(
                originalQuery, researcherId, explicitlyRequestedMcpTool, toolCollection, plan, assignedSubtasks);
        if (Boolean.FALSE.equals(parentRequest.getOnline())
                && StringUtils.isBlank(requiredEvidenceTool)
                && explicitMcpPreflight == null) {
            return offlineEvidenceGap(researcherId, assignedSections, assignedSubtasks, startedAt);
        }
        List<ToolCall> preflightToolCalls = new ArrayList<>();
        if (requiredEvidenceTool != null) {
            preflightToolCalls.add(researchEvidenceToolCall(
                    originalQuery, researcherId, requiredEvidenceTool, assignedSubtasks, plan));
        }
        if (explicitMcpPreflight != null) {
            preflightToolCalls.add(explicitMcpPreflight);
        }
        String prompt = buildPrompt(originalQuery, plan, researcherIndex, assignedSubtasks,
                requiredEvidenceTool, explicitlyRequestedMcpTool);
        childContext.setQuery(prompt);
        childContext.setToolCollection(toolCollection);
        // Research evidence is owned by the bounded system pipeline: optional
        // search preflight followed by deterministic fetch/extract.  The
        // composition turn must never let the model invent an extract_evidence
        // source (especially for offline requests where no search preflight is
        // available); it may only write an explicit evidence gap.
        childContext.setToolInvocationContract(ToolInvocationContract.systemPreflightOnly(
                preflightToolCalls.stream().map(call -> call.getFunction().getName()).toList()));

        List<ToolExecutionEvidence> before = parentContext.snapshotToolExecutionEvidence();
        AgentRunBudget budget = AgentRunBudget.defaults()
                .withMaxTurns(4)
                .withMaxToolCalls(Math.max(1, assignedSubtasks.stream()
                        .mapToInt(ResearchSubtask::maxToolCalls)
                        .sum()) + (explicitMcpPreflight == null ? 0 : 1))
                .withMaxDurationMillis(120_000L);
        AgentHarnessFacade.ToolLoopResult loopResult = agentHarnessFacade.runToolLoop(
                childContext,
                new AgentHarnessFacade.ToolLoopRequest(prompt, budget, false, preflightToolCalls));
        AgentLoop agentLoop = loopResult.agentLoop();
        String markdown = StringUtils.defaultString(loopResult.answer());
        if (agentLoop.getStopReason() == AgentStopReason.MODEL_ERROR && isQuotaFailure(agentLoop, markdown)) {
            throw new QuotaInsufficientException(quotaFailureMessage(agentLoop, markdown));
        }
        List<String> gaps = new ArrayList<>();
        if (agentLoop.getState() != AgentState.FINISHED) {
            gaps.add("分支未正常完成：" + agentLoop.getStopReason());
        }
        childContext.setToolInvocationContract(ToolInvocationContract.none());
        runFetchedEvidencePipeline(childContext, before);
        List<ResearchEvidencePacket> evidence = evidenceProducedAfter(before, parentContext.snapshotToolExecutionEvidence());
        if (!assignedSections.isEmpty() && evidence.isEmpty()) {
            gaps.add("未从工具结果获得包含 URL、标题和摘录的可引用证据");
        }
        return new ResearchBranchResult(
                researcherId,
                assignedSections,
                markdown,
                evidence,
                List.of(),
                gaps,
                startedAt,
                System.currentTimeMillis()
        );
    }

    /**
     * Offline research without an admitted search/MCP capability has no
     * trustworthy source material. Do not invoke a model merely to restate that
     * fact: providers can emit tool-shaped text even when no function schema is
     * exposed, which would turn a harmless evidence gap into a failed fake
     * extraction. The graph still fans out/fans in these bounded branch results.
     */
    private ResearchBranchResult offlineEvidenceGap(String researcherId,
                                                    List<String> assignedSections,
                                                    List<ResearchSubtask> assignedSubtasks,
                                                    long startedAt) {
        String objectives = assignedSubtasks.stream()
                .map(ResearchSubtask::objective)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.joining("；"));
        String gap = "当前离线运行未配置可用的 search_web/fetch_page 或已点名的只读 MCP 预调用；"
                + "未执行 search → fetch → extract，因此不生成可引用证据。";
        String markdown = "## " + researcherId + "\n\n"
                + "研究范围：" + StringUtils.defaultIfBlank(objectives, "当前分配章节") + "\n\n"
                + "- 证据缺口：" + gap + "\n";
        return new ResearchBranchResult(researcherId, assignedSections, markdown,
                List.of(), List.of(), List.of(gap), startedAt, System.currentTimeMillis());
    }

    private String buildPrompt(String query,
                               ResearchPlan plan,
                               int researcherIndex,
                               List<ResearchSubtask> assignedSubtasks,
                               String requiredEvidenceTool,
                               String explicitlyRequestedMcpTool) {
        String contracts = assignedSubtasks.stream()
                .map(task -> "- id=" + task.id()
                        + "; 目标=" + task.objective()
                        + "; 允许工具=" + task.allowedTools()
                        + "; 工具预算=" + task.maxToolCalls()
                        + "; 最少证据=" + task.minimumEvidence()
                        + "; 输出=" + task.outputSchema())
                .collect(java.util.stream.Collectors.joining("\n"));
        String evidenceDirective = StringUtils.isBlank(requiredEvidenceTool)
                ? "- 当前运行时没有可用的允许联网工具；不要编造来源，直接写明证据缺口。"
                : "- 系统会先通过当前 Agent 的 ToolDispatcher 尝试 " + requiredEvidenceTool
                + "，其实际结果是唯一来源事实；工具失败或没有 URL 时直接写证据缺口。";
        String mcpDirective = StringUtils.isBlank(explicitlyRequestedMcpTool)
                ? ""
                : "\n- 用户已明确点名 " + explicitlyRequestedMcpTool
                + "；系统只会以受 Schema、权限和额度边界约束的预调用方式执行它，"
                + "其返回值只能作为辅助材料，不能替代带 URL 的可引用证据。";
        return """
                你是熊博士深度调研的第 %d 路 Researcher。
                原始问题：%s
                报告标题：%s
                子任务契约：
                %s

                要求：
                - 只使用契约允许的工具，且不超过该子任务的工具预算；工具不可用时直接写证据缺口。
                %s
                - 只有系统完成 search → fetch → extract 后生成的 evidence id 才能用于对外结论；搜索摘要只是候选来源。
                - 输出 Markdown，并在末尾输出一个符合契约的 JSON 代码块；不要把模型推测、无 URL 文本或工具名伪装成来源。
                - 拿到材料后立即给最终 Markdown，不要等待确认或继续拆任务。
                %s
                """.formatted(researcherIndex, StringUtils.defaultString(query), plan.title(), contracts,
                evidenceDirective, mcpDirective);
    }

    private static List<String> activeToolNames(ToolCollection toolCollection) {
        List<String> names = new ArrayList<>();
        if (toolCollection == null) {
            return names;
        }
        if (toolCollection.getToolMap() != null) {
            names.addAll(toolCollection.getToolMap().keySet());
        }
        if (toolCollection.getMcpToolMap() != null) {
            names.addAll(toolCollection.getMcpToolMap().keySet());
        }
        return names;
    }

    private ToolCollection restrictToSubtaskTools(ToolCollection toolCollection,
                                                   List<ResearchSubtask> assignedSubtasks,
                                                   String explicitlyRequestedMcpTool) {
        if (toolCollection == null) {
            return new ToolCollection();
        }
        Set<String> allowedNames = new LinkedHashSet<>();
        for (ResearchSubtask task : assignedSubtasks) {
            if (task != null && task.allowedTools() != null) {
                allowedNames.addAll(task.allowedTools());
            }
        }
        if (StringUtils.isNotBlank(explicitlyRequestedMcpTool)) {
            allowedNames.add(explicitlyRequestedMcpTool);
        }
        return toolCollection.selectedView(allowedNames);
    }

    /**
     * A native DEEP plan deliberately limits normal researcher tools to its
     * evidence contract. Preserve that boundary, but retain one MCP capability
     * when (and only when) the authenticated request explicitly names a unique
     * MCP entry already present in the run-local catalog. The catalog has
     * already applied client bindings, enabled status, online mode and schema
     * discovery, so this method cannot make an arbitrary remote tool reachable.
     */
    static String explicitlyRequestedMcpTool(ToolCollection catalog, String requestQuery) {
        if (catalog == null || catalog.getMcpToolMap().isEmpty()) {
            return null;
        }
        List<String> explicitlyNamedTools = ExplicitToolChoicePolicy.resolveExplicitToolNames(
                requestQuery, 1, activeToolNames(catalog));
        List<String> explicitlyNamedMcpTools = explicitlyNamedTools.stream()
                .filter(name -> catalog.getMcpTool(name) != null)
                .toList();
        String selected = explicitlyNamedMcpTools.size() == 1 ? explicitlyNamedMcpTools.getFirst() : null;
        log.info("{} deep research explicit MCP candidates={} selected={} catalogMcpCount={}",
                catalog.getAgentContext() == null ? null : catalog.getAgentContext().getRequestId(),
                explicitlyNamedMcpTools, selected, catalog.getMcpToolMap().size());
        return selected;
    }

    /**
     * System preflight is intentionally narrower than generic MCP execution:
     * it is limited to a configured read-only schema with a single required
     * string {@code query} parameter. Other explicitly requested MCP tools do
     * not receive invented arguments from the research graph and remain
     * uninvoked by this bounded composition turn.
     */
    private ToolCall explicitlyRequestedMcpPreflight(String parentQuery,
                                                      String researcherId,
                                                      String toolName,
                                                      ToolCollection toolCollection,
                                                      ResearchPlan plan,
                                                      List<ResearchSubtask> assignedSubtasks) {
        McpToolInfo tool = toolCollection == null ? null : toolCollection.getMcpTool(toolName);
        if (!isQueryOnlyReadOnlyMcp(tool)) {
            return null;
        }
        try {
            String query = StringUtils.abbreviate(StringUtils.defaultIfBlank(parentQuery, plan.title()), 200);
            return ToolCall.builder()
                    .id("research-mcp:" + researcherId + ":" + toolName + ":"
                            + researchAttemptKey(plan, assignedSubtasks))
                    .type("function")
                    .function(ToolCall.Function.builder()
                            .name(toolName)
                            .arguments(JSON.writeValueAsString(Map.of("query", query)))
                            .build())
                    .build();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize the research MCP tool input.", error);
        }
    }

    private boolean isQueryOnlyReadOnlyMcp(McpToolInfo tool) {
        if (tool == null || tool.getSideEffect() != ToolSideEffect.READ_ONLY
                || StringUtils.isBlank(tool.getParameters())) {
            return false;
        }
        try {
            JsonNode schema = JSON.readTree(tool.getParameters());
            JsonNode query = schema.path("properties").path("query");
            if (!query.isObject() || !"string".equals(query.path("type").asText())) {
                return false;
            }
            JsonNode required = schema.path("required");
            if (!required.isArray()) {
                return true;
            }
            for (JsonNode requiredName : required) {
                if (!"query".equals(requiredName.asText())) {
                    return false;
                }
            }
            return true;
        } catch (JsonProcessingException error) {
            return false;
        }
    }

    /**
     * A research branch uses the normal Agent Loop, but the graph already owns
     * DEEP planning and review. Select the query-capable network tool that the
     * branch can dispatch through the normal ToolDispatcher before it asks the
     * model to compose its bounded section.
     */
    private String requiredNetworkEvidenceTool(ToolCollection toolCollection,
                                               List<ResearchSubtask> assignedSubtasks) {
        if (toolCollection == null || assignedSubtasks == null || assignedSubtasks.isEmpty()) {
            return null;
        }
        Set<String> evidenceTools = new LinkedHashSet<>();
        for (ResearchSubtask subtask : assignedSubtasks) {
            if (subtask != null && subtask.minimumEvidence() > 0 && subtask.allowedTools() != null) {
                evidenceTools.addAll(subtask.allowedTools());
            }
        }
        if (evidenceTools.isEmpty()) {
            return null;
        }
        List<String> activeTools = activeToolNames(toolCollection);
        if (evidenceTools.contains("search_web") && activeTools.contains("search_web")) {
            return "search_web";
        }
        return evidenceTools.contains("deep_search") && activeTools.contains("deep_search")
                && ExplicitToolChoicePolicy.isNetworkLookupToolName("deep_search")
                ? "deep_search"
                : null;
    }

    private ToolCall researchEvidenceToolCall(String parentQuery,
                                               String researcherId,
                                               String toolName,
                                               List<ResearchSubtask> assignedSubtasks,
                                               ResearchPlan plan) {
        String taskQuery = assignedSubtasks.stream()
                .map(ResearchSubtask::objective)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.joining("\n"));
        String searchQuery = StringUtils.defaultIfBlank(taskQuery, parentQuery);
        try {
            return ToolCall.builder()
                    .id("research-evidence:" + researcherId + ":" + toolName + ":"
                            + researchAttemptKey(plan, assignedSubtasks))
                    .type("function")
                    .function(ToolCall.Function.builder()
                            .name(toolName)
                            .arguments(JSON.writeValueAsString(Map.of("query", searchQuery)))
                            .build())
                    .build();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize the research evidence tool input.", error);
        }
    }

    /**
     * A Reviewer-directed revision is a new bounded research operation, not a
     * replay of the initial provider admission. The key stays stable for the
     * same restricted subtask set while differing from the initial plan.
     */
    private String researchAttemptKey(ResearchPlan plan, List<ResearchSubtask> assignedSubtasks) {
        String taskIds = assignedSubtasks.stream()
                .map(ResearchSubtask::id)
                .collect(java.util.stream.Collectors.joining(","));
        return Integer.toUnsignedString(java.util.Objects.hash(
                plan.subtasks().size(), taskIds, assignedSubtasks.size()));
    }

    private boolean isQuotaFailure(AgentLoop agentLoop, String markdown) {
        Message lastMessage = agentLoop.getMemory() == null ? null : agentLoop.getMemory().getLastMessage();
        String normalized = (StringUtils.defaultString(markdown) + "\n"
                + (lastMessage == null ? "" : StringUtils.defaultString(lastMessage.getContent())))
                .toLowerCase(Locale.ROOT);
        return normalized.contains("quota")
                || normalized.contains("insufficient")
                || normalized.contains("配额不足")
                || normalized.contains("额度不足")
                || normalized.contains("余额不足");
    }

    private String quotaFailureMessage(AgentLoop agentLoop, String markdown) {
        Message lastMessage = agentLoop.getMemory() == null ? null : agentLoop.getMemory().getLastMessage();
        String message = lastMessage == null ? "" : StringUtils.defaultString(lastMessage.getContent());
        return StringUtils.defaultIfBlank(message, StringUtils.defaultIfBlank(markdown, "额度不足，无法执行深度调研。"));
    }

    /** Search candidates become report evidence only after deterministic fetch and extraction. */
    private void runFetchedEvidencePipeline(AgentContext context, List<ToolExecutionEvidence> before) {
        int ordinal = 0;
        for (SearchCandidate candidate : searchCandidatesAfter(before, context.snapshotToolExecutionEvidence())) {
            if (ordinal++ >= MAX_FETCHED_CANDIDATES_PER_BRANCH) {
                break;
            }
            ToolExecutionOutcome fetched = agentHarnessFacade.executeTool(context,
                    toolCall("fetch_page", Map.of("url", candidate.url()), ordinal));
            if (fetched == null || !fetched.isSuccess() || !(fetched.getStructuredOutput() instanceof FetchedPageToolOutput page)
                    || StringUtils.isAnyBlank(page.getSourceId(), page.getFinalUrl(), page.getContent(), page.getContentHash())) {
                continue;
            }
            agentHarnessFacade.executeTool(context, toolCall("extract_evidence", Map.of(
                    "source_id", page.getSourceId(),
                    "source_url", page.getFinalUrl(),
                    "title", StringUtils.defaultIfBlank(page.getTitle(), candidate.title()),
                    "content", page.getContent(),
                    "content_hash", page.getContentHash(),
                    "fetched_at_epoch_millis", page.getFetchedAtEpochMillis(),
                    "source_type", "FETCHED_PAGE",
                    "claim", StringUtils.defaultIfBlank(candidate.summary(), candidate.title()),
                    "retrieval_trace_id", page.getSourceId(),
                    "offline_fixture", page.isOfflineFixture()), ordinal));
        }
    }

    private List<SearchCandidate> searchCandidatesAfter(List<ToolExecutionEvidence> before,
                                                        List<ToolExecutionEvidence> after) {
        List<ToolExecutionEvidence> delta = new ArrayList<>(after);
        delta.removeAll(before);
        List<SearchCandidate> candidates = new ArrayList<>();
        for (ToolExecutionEvidence evidence : delta) {
            if (!evidence.isSuccess() || evidence.isReused()
                    || !("search_web".equals(evidence.getToolName()) || "deep_search".equals(evidence.getToolName()))
                    || !(evidence.getStructuredOutput() instanceof DeepSearchToolOutput searchOutput)) {
                continue;
            }
            for (DeepSearchStage stage : searchOutput.getStages()) {
                if (stage == null || !"search".equals(stage.getStage())) {
                    continue;
                }
                for (DeepSearchQueryResult result : stage.getResults()) {
                    if (result == null) {
                        continue;
                    }
                    for (DeepSearchDoc doc : result.getDocs()) {
                        if (doc != null && (StringUtils.startsWithIgnoreCase(doc.getLink(), "https://")
                                || StringUtils.startsWithIgnoreCase(doc.getLink(), "http://"))) {
                            candidates.add(new SearchCandidate(doc.getTitle(), doc.getLink(), doc.getSummary()));
                        }
                    }
                }
            }
        }
        return candidates.stream().distinct().toList();
    }

    private ToolCall toolCall(String name, Map<String, Object> input, int ordinal) {
        try {
            return ToolCall.builder().id("p90-evidence:" + name + ":" + ordinal + ":" + System.nanoTime())
                    .type("function")
                    .function(ToolCall.Function.builder().name(name).arguments(JSON.writeValueAsString(input)).build())
                    .build();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize P90 evidence tool input", error);
        }
    }

    private List<ResearchEvidencePacket> evidenceProducedAfter(List<ToolExecutionEvidence> before,
                                                               List<ToolExecutionEvidence> after) {
        List<ToolExecutionEvidence> delta = new ArrayList<>(after);
        delta.removeAll(before);
        List<ResearchEvidencePacket> packets = new ArrayList<>();
        for (ToolExecutionEvidence evidence : delta) {
            if (!evidence.isSuccess() || evidence.isReused()
                    || !"extract_evidence".equals(evidence.getToolName())
                    || !(evidence.getStructuredOutput() instanceof ExtractedEvidenceToolOutput extracted)
                    || !extracted.isFetchedSource()) {
                continue;
            }
            int sourceIndex = 0;
            for (ExtractedEvidenceToolOutput.Excerpt excerpt : extracted.getExcerpts()) {
                if (excerpt == null || StringUtils.isBlank(excerpt.getQuote())) {
                    continue;
                }
                String claimStatement = StringUtils.defaultIfBlank(extracted.getClaim(), evidence.getToolCallId());
                ResearchEvidencePacket packet = new ResearchEvidencePacket(
                        canonicalClaimId(claimStatement),
                        StringUtils.defaultIfBlank(extracted.getTitle(), "fetched source"), extracted.getSourceUrl(),
                        excerpt.getQuote(), evidence.getToolCallId() + "-" + (++sourceIndex), extracted.getContentHash(),
                        extracted.getFetchedAtEpochMillis(), 0L, extracted.getSourceType(), "UNASSESSED", "UNKNOWN",
                        StringUtils.defaultIfBlank(extracted.getRetrievalTraceId(), evidence.getToolCallId()), claimStatement,
                        "SUPPORTS", excerpt.getStartOffset(), excerpt.getEndOffset(), extracted.isOfflineFixture());
                if (packet.isFinalReportEvidence()) {
                    packets.add(packet);
                }
            }
        }
        return List.copyOf(packets);
    }

    /** A claim is a fact statement, not a database key; persist a stable bounded identifier separately. */
    static String canonicalClaimId(String claimStatement) {
        return "claim-" + UUID.nameUUIDFromBytes(StringUtils.defaultString(claimStatement)
                .getBytes(StandardCharsets.UTF_8));
    }

    private record BranchPrinter(Printer delegate) implements Printer {
        @Override
        public void send(AgentStreamEvent event) {
            if (delegate != null) {
                if (event instanceof AgentStreamEvent.ToolStart
                        || event instanceof AgentStreamEvent.ToolEnd
                        || event instanceof AgentStreamEvent.Paused
                        || event instanceof AgentStreamEvent.ResumeStart) {
                    delegate.send(event);
                }
            }
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isAborted() {
            return delegate != null && delegate.isAborted();
        }
    }

    private record SearchCandidate(String title, String url, String summary) {
    }
}
