package org.wwz.ai.infrastructure.reactor.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.entity.LlmInvocation;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.memory.FileArtifactMemory;
import org.wwz.ai.domain.agent.memory.ReactCycleMemory;
import org.wwz.ai.domain.agent.memory.RunHistoryMemory;
import org.wwz.ai.domain.agent.memory.SessionContextMemoryService;
import org.wwz.ai.domain.agent.memory.SessionHistoryMemory;
import org.wwz.ai.domain.agent.memory.ToolCallMemory;
import org.wwz.ai.domain.agent.runtime.llm.TokenCounter;
import org.wwz.ai.infrastructure.dao.reactor.IArtifactLedgerDao;
import org.wwz.ai.infrastructure.dao.reactor.ILlmInvocationLedgerDao;
import org.wwz.ai.infrastructure.dao.reactor.IToolInvocationLedgerDao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 单会话上下文记忆服务实现（三层记忆中的「中期/会话记忆」）。
 *
 * <p>读取策略：最近 {@code recentRunWindow} 轮 run 逐字保留（完整 ReAct 循环明细），
 * 更早的 run 用其在账本里已生成的每轮总结（{@code final_summary_text}）做「摘要压缩」，
 * 替代此前「超预算直接硬截断丢历史」的做法。整体仍受 token 预算约束。</p>
 *
 * <p>兼容：不传 {@code recentRunWindow} 的旧构造器等价于「全部逐字 + 预算保留最新」的历史行为，
 * 仅当通过带窗口的构造器（生产 Spring 装配）且历史轮次超过窗口时才启用摘要压缩。</p>
 */
@Service
public class SessionContextMemoryServiceImpl implements SessionContextMemoryService {

    private static final int DEFAULT_MAX_HISTORY_DIALOGUE_TOKENS = 12000;
    private static final String HISTORY_DIALOGUE_HEADER = "## 单会话历史记忆";
    private static final String HISTORY_DIALOGUE_HEADER_WITH_SEPARATOR = HISTORY_DIALOGUE_HEADER + "\n\n";
    private static final String OLDER_SUMMARY_HEADER = "### 更早对话摘要（压缩）";
    private static final int OLDER_QUERY_MAX_CHARS = 200;
    private static final int OLDER_SUMMARY_MAX_CHARS = 400;

    private final ExecutionLedgerQueryService executionLedgerQueryService;
    private final ILlmInvocationLedgerDao llmInvocationLedgerDao;
    private final IToolInvocationLedgerDao toolInvocationLedgerDao;
    private final IArtifactLedgerDao artifactLedgerDao;
    private final TokenCounter tokenCounter;
    private final int maxHistoryDialogueTokens;
    /** 最近逐字保留的 run 数；Integer.MAX_VALUE 表示不压缩（等价旧行为）。 */
    private final int recentRunWindow;

    public SessionContextMemoryServiceImpl(ExecutionLedgerQueryService executionLedgerQueryService,
                                           ILlmInvocationLedgerDao llmInvocationLedgerDao,
                                           IToolInvocationLedgerDao toolInvocationLedgerDao,
                                           IArtifactLedgerDao artifactLedgerDao) {
        this(
                executionLedgerQueryService,
                llmInvocationLedgerDao,
                toolInvocationLedgerDao,
                artifactLedgerDao,
                DEFAULT_MAX_HISTORY_DIALOGUE_TOKENS,
                Integer.MAX_VALUE
        );
    }

    public SessionContextMemoryServiceImpl(ExecutionLedgerQueryService executionLedgerQueryService,
                                           ILlmInvocationLedgerDao llmInvocationLedgerDao,
                                           IToolInvocationLedgerDao toolInvocationLedgerDao,
                                           IArtifactLedgerDao artifactLedgerDao,
                                           int maxHistoryDialogueTokens) {
        this(
                executionLedgerQueryService,
                llmInvocationLedgerDao,
                toolInvocationLedgerDao,
                artifactLedgerDao,
                maxHistoryDialogueTokens,
                Integer.MAX_VALUE
        );
    }

    @Autowired
    public SessionContextMemoryServiceImpl(ExecutionLedgerQueryService executionLedgerQueryService,
                                           ILlmInvocationLedgerDao llmInvocationLedgerDao,
                                           IToolInvocationLedgerDao toolInvocationLedgerDao,
                                           IArtifactLedgerDao artifactLedgerDao,
                                           @Value("${autobots.autoagent.memory.max-tokens:${autobots.autoagent.history-dialogue.max-tokens:12000}}") int maxHistoryDialogueTokens,
                                           @Value("${autobots.autoagent.memory.recent-run-window:3}") int recentRunWindow) {
        this.executionLedgerQueryService = executionLedgerQueryService;
        this.llmInvocationLedgerDao = llmInvocationLedgerDao;
        this.toolInvocationLedgerDao = toolInvocationLedgerDao;
        this.artifactLedgerDao = artifactLedgerDao;
        this.tokenCounter = new TokenCounter();
        this.maxHistoryDialogueTokens = normalizeMaxHistoryDialogueTokens(maxHistoryDialogueTokens);
        this.recentRunWindow = recentRunWindow <= 0 ? Integer.MAX_VALUE : recentRunWindow;
    }

    @Override
    public String buildHistoryDialogue(String sessionId, String currentRequestId) {
        if (StringUtils.isBlank(sessionId)) {
            return "";
        }
        List<DialogueRunView> orderedRuns = queryOrderedHistoryRuns(sessionId, currentRequestId);
        if (orderedRuns.isEmpty()) {
            return "";
        }
        // 历史轮次不超过窗口（或未启用压缩）时，走原「全部逐字 + 预算保留最新」路径，保持既有行为与输出格式。
        if (recentRunWindow == Integer.MAX_VALUE || orderedRuns.size() <= recentRunWindow) {
            SessionHistoryMemory memory = assembleSessionHistoryMemory(sessionId, currentRequestId, orderedRuns);
            return formatHistoryDialogueWithinTokenBudget(memory);
        }
        return buildHistoryDialogueWithCompression(sessionId, currentRequestId, orderedRuns);
    }

    private List<DialogueRunView> queryOrderedHistoryRuns(String sessionId, String currentRequestId) {
        return executionLedgerQueryService.querySessionRuns(sessionId).stream()
                .filter(run -> run != null && run.getId() != null)
                .filter(run -> !StringUtils.equals(run.getRequestId(), currentRequestId))
                .toList();
    }

    /**
     * 中期记忆压缩组装：更早 run 用每轮总结压缩为摘要段，最近 K 轮逐字保留，整体受 token 预算约束。
     */
    private String buildHistoryDialogueWithCompression(String sessionId,
                                                       String currentRequestId,
                                                       List<DialogueRunView> orderedRuns) {
        int splitIndex = orderedRuns.size() - recentRunWindow;
        List<DialogueRunView> olderRuns = new ArrayList<>(orderedRuns.subList(0, splitIndex));
        List<DialogueRunView> recentRuns = new ArrayList<>(orderedRuns.subList(splitIndex, orderedRuns.size()));

        String olderSummary = buildOlderRunsSummary(olderRuns);
        int headerTokens = tokenCounter.countText(HISTORY_DIALOGUE_HEADER_WITH_SEPARATOR);
        int remainingBudget = maxHistoryDialogueTokens - headerTokens - tokenCounter.countText(olderSummary) - 2;
        if (remainingBudget < 0) {
            remainingBudget = 0;
        }

        SessionHistoryMemory recentMemory = assembleSessionHistoryMemory(sessionId, currentRequestId, recentRuns);
        String recentBody = renderRunBlocksWithinBudget(recentMemory, remainingBudget);

        StringBuilder builder = new StringBuilder(HISTORY_DIALOGUE_HEADER_WITH_SEPARATOR);
        builder.append(olderSummary);
        if (StringUtils.isNotBlank(recentBody)) {
            builder.append("\n\n").append(recentBody);
        }
        return builder.toString();
    }

    /**
     * 摘要压缩：把更早 run 逐轮压成「用户诉求 + 结论」，复用账本里已由 LLM 生成的每轮 final_summary_text。
     */
    private String buildOlderRunsSummary(List<DialogueRunView> olderRuns) {
        StringBuilder builder = new StringBuilder(OLDER_SUMMARY_HEADER).append('\n');
        int index = 0;
        for (DialogueRunView run : olderRuns) {
            index++;
            String query = truncateText(valueOrEmpty(run.getQueryText()), OLDER_QUERY_MAX_CHARS);
            String summary = truncateText(valueOrEmpty(run.getFinalSummaryText()), OLDER_SUMMARY_MAX_CHARS);
            builder.append("- 第").append(index).append("轮 用户：").append(query)
                    .append("；结论：").append(summary).append('\n');
        }
        String text = builder.toString().trim();
        int budget = Math.max(0, maxHistoryDialogueTokens * 4 / 10);
        if (budget > 0 && tokenCounter.countText(text) > budget) {
            text = text.substring(0, Math.min(text.length(), budget)).trim();
        }
        return text;
    }

    /**
     * 渲染最近若干 run 的逐字块，在预算内保留最新的若干轮（不含主标题）。
     */
    private String renderRunBlocksWithinBudget(SessionHistoryMemory memory, int budgetTokens) {
        if (memory == null || memory.getRuns() == null || memory.getRuns().isEmpty()) {
            return "";
        }
        LinkedList<String> keptRunBlocks = new LinkedList<>();
        for (int index = memory.getRuns().size() - 1; index >= 0; index--) {
            String runBlock = formatRunHistory(memory.getRuns().get(index));
            keptRunBlocks.addFirst(runBlock);
            if (tokenCounter.countText(String.join("\n\n", keptRunBlocks)) <= budgetTokens) {
                continue;
            }
            keptRunBlocks.removeFirst();
            if (keptRunBlocks.isEmpty() && budgetTokens > 0) {
                String truncated = runBlock.length() <= budgetTokens
                        ? runBlock
                        : runBlock.substring(0, budgetTokens);
                truncated = truncated.trim();
                if (StringUtils.isNotBlank(truncated)) {
                    keptRunBlocks.add(truncated);
                }
            }
            break;
        }
        return String.join("\n\n", keptRunBlocks);
    }

    private SessionHistoryMemory assembleSessionHistoryMemory(String sessionId,
                                                              String currentRequestId,
                                                              List<DialogueRunView> orderedRuns) {
        SessionHistoryMemory sessionHistoryMemory = SessionHistoryMemory.builder()
                .sessionId(sessionId)
                .currentRequestId(currentRequestId)
                .runs(new ArrayList<>())
                .build();
        if (orderedRuns.isEmpty()) {
            return sessionHistoryMemory;
        }

        List<Long> runIds = orderedRuns.stream()
                .map(DialogueRunView::getId)
                .toList();
        List<LlmInvocation> llmInvocations = llmInvocationLedgerDao.queryByRunIds(runIds);
        List<Long> llmInvocationIds = llmInvocations.stream()
                .map(LlmInvocation::getId)
                .filter(id -> id != null)
                .toList();
        List<ToolInvocation> toolInvocations = llmInvocationIds.isEmpty()
                ? List.of()
                : toolInvocationLedgerDao.queryByLlmInvocationIds(llmInvocationIds);
        List<Long> toolInvocationIds = toolInvocations.stream()
                .map(ToolInvocation::getId)
                .filter(id -> id != null)
                .toList();
        List<ArtifactRecord> inputArtifacts = artifactLedgerDao.queryInputArtifactsByRunIds(runIds);
        List<ArtifactRecord> outputArtifacts = toolInvocationIds.isEmpty()
                ? List.of()
                : artifactLedgerDao.queryByToolInvocationIds(toolInvocationIds);

        Map<Long, List<LlmInvocation>> llmInvocationsByRunId = llmInvocations.stream()
                .collect(Collectors.groupingBy(LlmInvocation::getRunId, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
        Map<Long, List<ToolInvocation>> toolInvocationsByLlmInvocationId = toolInvocations.stream()
                .collect(Collectors.groupingBy(ToolInvocation::getLlmInvocationId, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
        Map<Long, List<FileArtifactMemory>> inputFilesByRunId = inputArtifacts.stream()
                .collect(Collectors.groupingBy(ArtifactRecord::getRunId, LinkedHashMap::new,
                        Collectors.mapping(this::toFileArtifactMemory, Collectors.toCollection(ArrayList::new))));
        Map<Long, List<FileArtifactMemory>> outputFilesByToolInvocationId = outputArtifacts.stream()
                .collect(Collectors.groupingBy(ArtifactRecord::getToolInvocationId, LinkedHashMap::new,
                        Collectors.mapping(this::toFileArtifactMemory, Collectors.toCollection(ArrayList::new))));

        for (DialogueRunView run : orderedRuns) {
            RunHistoryMemory runHistoryMemory = RunHistoryMemory.builder()
                    .runId(run.getId())
                    .requestId(run.getRequestId())
                    .sessionId(run.getSessionId())
                    .entryAgent(run.getEntryAgent())
                    .sessionInputFiles(new ArrayList<>(inputFilesByRunId.getOrDefault(run.getId(), List.of())))
                    .reactCycles(new ArrayList<>())
                    .build();

            // 记忆锚点是 llmInvocation，而不是 run。
            // 一个 llmInvocation 对应一次完整的 ReAct 循环，工具调用只是该循环下的动作明细。
            for (LlmInvocation llmInvocation : llmInvocationsByRunId.getOrDefault(run.getId(), List.of())) {
                ReactCycleMemory cycleMemory = ReactCycleMemory.builder()
                        .runId(run.getId())
                        .requestId(run.getRequestId())
                        .llmInvocationId(llmInvocation.getId())
                        .invocationSeq(llmInvocation.getInvocationSeq())
                        .agentName(llmInvocation.getAgentName())
                        .stepNo(llmInvocation.getStepNo())
                        .thoughtContent(StringUtils.defaultString(llmInvocation.getResponseText()))
                        .toolCalls(new ArrayList<>())
                        .build();
                for (ToolInvocation toolInvocation : toolInvocationsByLlmInvocationId.getOrDefault(llmInvocation.getId(), List.of())) {
                    cycleMemory.getToolCalls().add(ToolCallMemory.builder()
                            .toolInvocationId(toolInvocation.getId())
                            .llmInvocationId(toolInvocation.getLlmInvocationId())
                            .toolCallId(toolInvocation.getToolCallId())
                            .dispatchIndex(toolInvocation.getDispatchIndex())
                            .agentName(toolInvocation.getAgentName())
                            .stepNo(toolInvocation.getStepNo())
                            .toolName(toolInvocation.getToolName())
                            .toolProvider(toolInvocation.getToolProvider())
                            .inputJson(StringUtils.defaultString(toolInvocation.getInputJson()))
                            .llmObservation(StringUtils.defaultString(toolInvocation.getLlmObservation()))
                            .files(new ArrayList<>(outputFilesByToolInvocationId.getOrDefault(toolInvocation.getId(), List.of())))
                            .build());
                }
                runHistoryMemory.getReactCycles().add(cycleMemory);
            }
            sessionHistoryMemory.getRuns().add(runHistoryMemory);
        }
        return sessionHistoryMemory;
    }

    private String formatHistoryDialogueWithinTokenBudget(SessionHistoryMemory sessionHistoryMemory) {
        if (sessionHistoryMemory == null || sessionHistoryMemory.getRuns() == null || sessionHistoryMemory.getRuns().isEmpty()) {
            return "";
        }
        LinkedList<String> keptRunBlocks = new LinkedList<>();
        for (int index = sessionHistoryMemory.getRuns().size() - 1; index >= 0; index--) {
            String runBlock = formatRunHistory(sessionHistoryMemory.getRuns().get(index));
            keptRunBlocks.addFirst(runBlock);
            String candidateHistoryDialogue = buildHistoryDialogueText(keptRunBlocks);
            if (tokenCounter.countText(candidateHistoryDialogue) <= maxHistoryDialogueTokens) {
                continue;
            }

            keptRunBlocks.removeFirst();
            if (keptRunBlocks.isEmpty()) {
                String truncatedLatestRunBlock = truncateRunBlockToFit(runBlock);
                if (StringUtils.isNotBlank(truncatedLatestRunBlock)) {
                    keptRunBlocks.add(truncatedLatestRunBlock);
                }
            }
            break;
        }
        return buildHistoryDialogueText(keptRunBlocks);
    }

    private String buildHistoryDialogueText(List<String> runBlocks) {
        if (runBlocks == null || runBlocks.isEmpty()) {
            return HISTORY_DIALOGUE_HEADER;
        }
        return HISTORY_DIALOGUE_HEADER_WITH_SEPARATOR + String.join("\n\n", runBlocks);
    }

    private String formatRunHistory(RunHistoryMemory run) {
        StringBuilder builder = new StringBuilder();
        builder.append("### Run ").append(valueOrEmpty(run.getRequestId())).append('\n');
        if (run.getSessionInputFiles() != null && !run.getSessionInputFiles().isEmpty()) {
            builder.append("[Session Input Files]\n");
            for (FileArtifactMemory file : run.getSessionInputFiles()) {
                builder.append("- fileName=").append(valueOrEmpty(file.getFileName()))
                        .append(", mimeType=").append(valueOrEmpty(file.getMimeType()))
                        .append(", fileSize=").append(valueOrEmpty(file.getFileSize()))
                        .append(", storageKey=").append(valueOrEmpty(file.getStorageKey()))
                        .append(", downloadUrl=").append(valueOrEmpty(file.getDownloadUrl()))
                        .append(", previewUrl=").append(valueOrEmpty(file.getPreviewUrl()))
                        .append('\n');
            }
            builder.append('\n');
        }
        for (ReactCycleMemory cycle : run.getReactCycles()) {
            builder.append("[ReAct Cycle ").append(valueOrEmpty(cycle.getInvocationSeq())).append("]\n");
            builder.append("Thought:\n").append(valueOrEmpty(cycle.getThoughtContent())).append("\n\n");
            builder.append("Tool Calls:\n");
            if (cycle.getToolCalls() == null || cycle.getToolCalls().isEmpty()) {
                builder.append("- none\n\n");
                continue;
            }
            int index = 1;
            for (ToolCallMemory toolCall : cycle.getToolCalls()) {
                builder.append(index++).append(". toolName=").append(valueOrEmpty(toolCall.getToolName())).append('\n');
                builder.append("   toolProvider=").append(valueOrEmpty(toolCall.getToolProvider())).append('\n');
                builder.append("   inputJson=").append(valueOrEmpty(toolCall.getInputJson())).append('\n');
                builder.append("   llmObservation=").append(valueOrEmpty(toolCall.getLlmObservation())).append('\n');
                builder.append("Files:\n");
                if (toolCall.getFiles() == null || toolCall.getFiles().isEmpty()) {
                    builder.append("- none\n");
                    continue;
                }
                for (FileArtifactMemory file : toolCall.getFiles()) {
                    builder.append("- artifactRole=").append(valueOrEmpty(file.getArtifactRole()))
                            .append(", fileName=").append(valueOrEmpty(file.getFileName()))
                            .append(", mimeType=").append(valueOrEmpty(file.getMimeType()))
                            .append(", fileSize=").append(valueOrEmpty(file.getFileSize()))
                            .append(", storageKey=").append(valueOrEmpty(file.getStorageKey()))
                            .append(", downloadUrl=").append(valueOrEmpty(file.getDownloadUrl()))
                            .append(", previewUrl=").append(valueOrEmpty(file.getPreviewUrl()))
                            .append('\n');
                }
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private String truncateRunBlockToFit(String runBlock) {
        int remainingTokens = maxHistoryDialogueTokens - tokenCounter.countText(HISTORY_DIALOGUE_HEADER_WITH_SEPARATOR);
        if (remainingTokens <= 0 || StringUtils.isBlank(runBlock)) {
            return "";
        }
        int truncateLength = Math.min(runBlock.length(), remainingTokens);

        // 当前 TokenCounter 仍按字符数近似计算，因此这里按字符裁剪即可稳定满足预算上限。
        return runBlock.substring(0, truncateLength).trim();
    }

    private int normalizeMaxHistoryDialogueTokens(int configuredMaxTokens) {
        return configuredMaxTokens > 0 ? configuredMaxTokens : DEFAULT_MAX_HISTORY_DIALOGUE_TOKENS;
    }

    private String truncateText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private FileArtifactMemory toFileArtifactMemory(ArtifactRecord artifactRecord) {
        return FileArtifactMemory.builder()
                .artifactId(artifactRecord.getId())
                .runId(artifactRecord.getRunId())
                .requestId(artifactRecord.getRequestId())
                .toolInvocationId(artifactRecord.getToolInvocationId())
                .toolCallId(artifactRecord.getToolCallId())
                .artifactRole(artifactRecord.getArtifactRole())
                .fileName(artifactRecord.getFileName())
                .storageKey(artifactRecord.getStorageKey())
                .downloadUrl(artifactRecord.getDownloadUrl())
                .previewUrl(artifactRecord.getPreviewUrl())
                .mimeType(artifactRecord.getMimeType())
                .fileSize(artifactRecord.getFileSize())
                .build();
    }

    private String valueOrEmpty(String value) {
        return StringUtils.defaultString(value);
    }

    private String valueOrEmpty(Number value) {
        return value == null ? "" : String.valueOf(value);
    }
}
