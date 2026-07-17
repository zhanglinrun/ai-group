package com.linrun.agent.domain.agent.ledger.replay;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.model.valobj.ConversationRoleVO;
import com.linrun.agent.domain.agent.ledger.model.ArtifactView;
import com.linrun.agent.domain.agent.ledger.model.ConversationHistoryDetail;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunView;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionView;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.ExecutionRunDetail;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationView;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationView;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.ledger.model.replay.ReplayFactBundle;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerQueryService;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ReportToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话历史详情聚合服务。
 */
@RequiredArgsConstructor
public class ConversationHistoryReplayService {

    private final ExecutionLedgerQueryService executionLedgerQueryService;
    private final ReplayProjector replayProjector;
    private final HistoryReplayPrinter historyReplayPrinter;

    public ConversationHistoryDetail queryConversationHistory(String sessionId) {
        if (StringUtils.isBlank(sessionId) || executionLedgerQueryService == null) {
            return null;
        }
        DialogueSessionView session = executionLedgerQueryService.querySession(sessionId);
        if (session == null) {
            return null;
        }
        List<DialogueRunView> runs = executionLedgerQueryService.querySessionRuns(sessionId);
        List<ConversationHistoryDetail.ConversationRunDetail> runDetails = new ArrayList<>();
        HistoryModeSnapshot historyModeSnapshot = HistoryModeSnapshot.defaultChat();
        if (CollectionUtils.isNotEmpty(runs)) {
            for (DialogueRunView run : runs) {
                if (run == null || StringUtils.isBlank(run.getRequestId())) {
                    continue;
                }
                // 历史详情严格以 run 为最小回放单元：
                // 先查单 run 明细，再交给共享 projector 产出与实时同构的 replay frames。
                ExecutionRunDetail runDetail = executionLedgerQueryService.queryRunDetail(run.getRequestId());
                ReplayFactBundle bundle = ReplayFactBundle.builder()
                        .run(runDetail == null ? run : runDetail.getRun())
                        .llmInvocations(runDetail == null ? List.of() : runDetail.getLlmInvocations())
                        .toolInvocations(runDetail == null ? List.of() : runDetail.getToolInvocations())
                        .artifacts(runDetail == null ? List.of() : runDetail.getArtifacts())
                        .build();
                List<GptProcessResult> replayFrames = replayProjector == null
                        ? List.of()
                        : replayProjector.projectHistoryFrames(bundle);
                historyModeSnapshot = resolveHistoryModeSnapshot(run, runDetail, replayFrames);
                // 展示级 run 元数据（模型 / tokens / 耗时）：优先取单 run 明细的账本聚合，回退列表视图。
                DialogueRunView metricsRun = (runDetail != null && runDetail.getRun() != null)
                        ? runDetail.getRun()
                        : run;
                runDetails.add(ConversationHistoryDetail.ConversationRunDetail.builder()
                        .requestId(run.getRequestId())
                        .status(run.getStatus())
                        .queryText(run.getQueryText())
                        .finalSummaryText(run.getFinalSummaryText())
                        .startedAt(run.getStartedAt())
                        .finishedAt(run.getFinishedAt())
                        .modelName(resolveRunModelName(runDetail))
                        .totalTokens(metricsRun == null ? null : metricsRun.getTotalTokensTotal())
                        .durationMs(metricsRun == null ? null : metricsRun.getDurationMs())
                        .replayFrames(historyReplayPrinter == null
                                ? replayFrames
                                : historyReplayPrinter.ensureReadableConclusion(run, replayFrames))
                        .build());
            }
        }

        return ConversationHistoryDetail.builder()
                .sessionId(session.getSessionId())
                .title(session.getTitle())
                .status(resolveSessionStatus(session, runs))
                .outputStyle(historyModeSnapshot.getOutputStyle())
                .executionMode(historyModeSnapshot.getExecutionMode())
                .role(resolveRole(runs))
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .runs(runDetails)
                .build();
    }

    /** Restores the unified execution mode encoded in entry_agent. */
    private HistoryModeSnapshot resolveHistoryModeSnapshot(DialogueRunView run,
                                                          ExecutionRunDetail runDetail,
                                                          List<GptProcessResult> replayFrames) {
        if (run == null) {
            return HistoryModeSnapshot.defaultChat();
        }

        String entryAgent = StringUtils.trimToEmpty(run.getEntryAgent());
        if (entryAgent.startsWith(ExecutionLedgerConstants.ENTRY_AGENT_LOOP_PREFIX)) {
            String mode = entryAgent.substring(ExecutionLedgerConstants.ENTRY_AGENT_LOOP_PREFIX.length())
                    .trim().toUpperCase(Locale.ROOT);
            return new HistoryModeSnapshot(
                    StringUtils.defaultIfBlank(resolveStructuredOutputStyle(runDetail, replayFrames), "chat"),
                    isExecutionMode(mode) ? mode : "STANDARD"
            );
        }
        // Package-local, read-only compatibility for persisted runs. These values
        // never leave replay and cannot select a runtime or persistence path.
        if (LegacyLedgerReplayCompatibility.isDeepEntry(entryAgent)) {
            return new HistoryModeSnapshot(
                    StringUtils.defaultIfBlank(resolveStructuredOutputStyle(runDetail, replayFrames), "docs"),
                    "DEEP"
            );
        }
        if (LegacyLedgerReplayCompatibility.isStandardEntry(entryAgent)) {
            return new HistoryModeSnapshot(
                    StringUtils.defaultIfBlank(resolveStructuredOutputStyle(runDetail, replayFrames), "chat"),
                    "STANDARD"
            );
        }
        return HistoryModeSnapshot.defaultChat();
    }

    private boolean isExecutionMode(String value) {
        return "AUTO".equals(value) || "STANDARD".equals(value) || "DEEP".equals(value);
    }

    /**
     * 结构化输出样式优先级：
     * 1. rich tool 强类型输出
     * 2. 历史 replay frame 中的 messageType
     * 3. 产物文件后缀
     */
    private String resolveStructuredOutputStyle(ExecutionRunDetail runDetail,
                                                List<GptProcessResult> replayFrames) {
        if (runDetail != null) {
            String styleFromTool = resolveOutputStyleFromToolInvocations(runDetail.getToolInvocations());
            if (StringUtils.isNotBlank(styleFromTool)) {
                return styleFromTool;
            }
        }

        String styleFromReplay = resolveOutputStyleFromReplayFrames(replayFrames);
        if (StringUtils.isNotBlank(styleFromReplay)) {
            return styleFromReplay;
        }

        if (runDetail != null) {
            return resolveOutputStyleFromArtifacts(runDetail.getArtifacts());
        }
        return null;
    }

    private String resolveOutputStyleFromToolInvocations(List<ToolInvocationView> toolInvocations) {
        if (CollectionUtils.isEmpty(toolInvocations)) {
            return null;
        }
        for (int index = toolInvocations.size() - 1; index >= 0; index -= 1) {
            ToolInvocationView invocation = toolInvocations.get(index);
            if (invocation == null) {
                continue;
            }
            ToolStructuredOutput structuredOutput = invocation.getStructuredOutput();
            if (structuredOutput instanceof ReportToolOutput reportToolOutput) {
                String style = normalizeOutputStyle(reportToolOutput.getFileType());
                if (StringUtils.isNotBlank(style)) {
                    return style;
                }
            }
        }
        return null;
    }

    private String resolveOutputStyleFromReplayFrames(List<GptProcessResult> replayFrames) {
        if (CollectionUtils.isEmpty(replayFrames)) {
            return null;
        }
        for (int index = replayFrames.size() - 1; index >= 0; index -= 1) {
            GptProcessResult replayFrame = replayFrames.get(index);
            if (replayFrame == null || replayFrame.getResultMap() == null) {
                continue;
            }
            Object eventDataObject = replayFrame.getResultMap().get("eventData");
            if (!(eventDataObject instanceof Map<?, ?> eventDataMap)) {
                continue;
            }
            Object resultMapObject = eventDataMap.get("resultMap");
            if (!(resultMapObject instanceof Map<?, ?> nestedResultMap)) {
                continue;
            }
            String style = normalizeOutputStyle(nestedResultMap.get("messageType"));
            if (StringUtils.isNotBlank(style)) {
                return style;
            }
        }
        return null;
    }

    private String resolveOutputStyleFromArtifacts(List<ArtifactView> artifacts) {
        if (CollectionUtils.isEmpty(artifacts)) {
            return null;
        }
        for (int index = artifacts.size() - 1; index >= 0; index -= 1) {
            ArtifactView artifact = artifacts.get(index);
            if (artifact == null) {
                continue;
            }
            String style = resolveOutputStyleFromFileName(artifact.getFileName());
            if (StringUtils.isNotBlank(style)) {
                return style;
            }
        }
        return null;
    }

    private String resolveOutputStyleFromFileName(String fileName) {
        if (StringUtils.isBlank(fileName) || !fileName.contains(".")) {
            return null;
        }
        String extension = StringUtils.substringAfterLast(fileName, ".").toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "html", "htm" -> "html";
            case "md", "markdown", "doc", "docx", "txt" -> "docs";
            case "ppt", "pptx" -> "ppt";
            case "csv", "xls", "xlsx" -> "table";
            default -> null;
        };
    }

    private String normalizeOutputStyle(Object rawType) {
        String normalized = StringUtils.trimToEmpty(rawType == null ? null : String.valueOf(rawType))
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "html" -> "html";
            case "markdown", "docs" -> "docs";
            case "ppt" -> "ppt";
            case "table", "data_analysis" -> "table";
            default -> null;
        };
    }

    /**
     * 取本轮实际使用的模型名：从后往前取最近一条带模型名的 LLM 调用（通常是最终总结调用）。
     */
    private String resolveRunModelName(ExecutionRunDetail runDetail) {
        if (runDetail == null || CollectionUtils.isEmpty(runDetail.getLlmInvocations())) {
            return null;
        }
        List<LlmInvocationView> invocations = runDetail.getLlmInvocations();
        for (int index = invocations.size() - 1; index >= 0; index -= 1) {
            LlmInvocationView invocation = invocations.get(index);
            if (invocation != null && StringUtils.isNotBlank(invocation.getModelName())) {
                return invocation.getModelName().trim();
            }
        }
        return null;
    }

    private ConversationRoleVO resolveRole(List<DialogueRunView> runs) {
        if (CollectionUtils.isNotEmpty(runs)) {
            for (int index = runs.size() - 1; index >= 0; index -= 1) {
                DialogueRunView run = runs.get(index);
                if (run == null) {
                    continue;
                }
                if (StringUtils.isBlank(run.getRoleAgentId())
                        && StringUtils.isBlank(run.getRoleAgentName())) {
                    return defaultRole();
                }
                return ConversationRoleVO.builder()
                        .agentId(StringUtils.trimToNull(run.getRoleAgentId()))
                        .agentName(StringUtils.defaultIfBlank(run.getRoleAgentName(), "默认助手"))
                        .available(true)
                        .defaultRole(false)
                        .build();
            }
        }
        return defaultRole();
    }

    private ConversationRoleVO defaultRole() {
        return ConversationRoleVO.builder()
                .agentId(null)
                .agentName("默认助手")
                .available(true)
                .defaultRole(true)
                .build();
    }

    /**
     * 会话历史对外复用 run 的整型状态，保持与账本一致，
     * 具体的字符串化交给 trigger 层统一收口，避免多个层次重复维护枚举。
     */
    private Integer resolveSessionStatus(DialogueSessionView session, List<DialogueRunView> runs) {
        if (session != null && session.getStatus() != null) {
            return session.getStatus();
        }
        if (CollectionUtils.isEmpty(runs)) {
            return ExecutionLedgerConstants.STATUS_RUNNING;
        }
        DialogueRunView latestRun = runs.get(runs.size() - 1);
        return latestRun == null || latestRun.getStatus() == null
                ? ExecutionLedgerConstants.STATUS_RUNNING
                : latestRun.getStatus();
    }

    private static final class HistoryModeSnapshot {

        private final String outputStyle;
        private final String executionMode;

        private HistoryModeSnapshot(String outputStyle, String executionMode) {
            this.outputStyle = outputStyle;
            this.executionMode = executionMode;
        }

        private static HistoryModeSnapshot defaultChat() {
            return new HistoryModeSnapshot("chat", "STANDARD");
        }

        private String getOutputStyle() {
            return outputStyle;
        }

        private String getExecutionMode() {
            return executionMode;
        }
    }
}
