package com.linrun.agent.domain.agent.ledger.replay;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerQueryService;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunClaim;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.ExecutionRunDetail;
import com.linrun.agent.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import com.linrun.agent.domain.agent.ledger.model.replay.ReplayFactBundle;
import com.linrun.agent.domain.agent.reactor.model.constant.Constants;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.printer.ReplayFrameSink;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replays an already-terminal dialogue run without re-entering tools, models, or billing.
 */
@Slf4j
@Service
public class DialogueRunReplayService {

    private final ExecutionLedgerQueryService executionLedgerQueryService;
    private final ReplayProjector replayProjector;
    private final AgentStreamEventStore streamEventStore;

    public DialogueRunReplayService(ExecutionLedgerQueryService executionLedgerQueryService,
                                    ReplayProjector replayProjector) {
        this(executionLedgerQueryService, replayProjector, (AgentStreamEventStore) null);
    }

    @Autowired
    public DialogueRunReplayService(ExecutionLedgerQueryService executionLedgerQueryService,
                                    ReplayProjector replayProjector,
                                    ObjectProvider<AgentStreamEventStore> streamEventStore) {
        this(executionLedgerQueryService, replayProjector, streamEventStore.getIfAvailable());
    }

    public DialogueRunReplayService(ExecutionLedgerQueryService executionLedgerQueryService,
                                    ReplayProjector replayProjector,
                                    AgentStreamEventStore streamEventStore) {
        this.executionLedgerQueryService = executionLedgerQueryService;
        this.replayProjector = replayProjector;
        this.streamEventStore = streamEventStore;
    }

    /**
     * ReplayProjector remains the single projection model. This adapter only changes live
     * stream completion semantics so the final result frame, and no earlier frame, closes SSE.
     */
    public String replay(Printer printer, DialogueRunClaim claim) {
        String fallbackSummary = StringUtils.defaultString(claim.getFinalSummaryText());
        if (printer instanceof ReplayFrameSink replayFrameSink && streamEventStore != null) {
            List<AgentStreamEventStore.StoredStreamEvent> stored =
                    streamEventStore.findByRequestId(claim.getRequestId());
            if (!stored.isEmpty()) {
                stored.forEach(event -> replayFrameSink.sendCanonicalReplay(
                        event.eventType(), event.eventJson()));
                return fallbackSummary;
            }
        }
        if (!(printer instanceof ReplayFrameSink replayFrameSink)
                || executionLedgerQueryService == null
                || replayProjector == null) {
            emitFallback(printer, claim.getRunStatus(), fallbackSummary,
                    claim.getErrorCode(), claim.getErrorMsg());
            return fallbackSummary;
        }

        try {
            ExecutionRunDetail detail = executionLedgerQueryService.queryRunDetail(claim.getRequestId());
            if (detail == null || detail.getRun() == null
                    || !Objects.equals(detail.getRun().getId(), claim.getRunId())
                    || !StringUtils.equals(detail.getRun().getOwnerId(), claim.getOwnerId())) {
                throw new IllegalStateException("claimed terminal run could not be loaded with matching ownership");
            }
            String finalSummary = StringUtils.defaultString(detail.getRun().getFinalSummaryText());
            ReplayFactBundle bundle = ReplayFactBundle.builder()
                    .run(detail.getRun())
                    .llmInvocations(detail.getLlmInvocations())
                    .toolInvocations(detail.getToolInvocations())
                    .artifacts(detail.getArtifacts())
                    .build();
            List<ProjectedReplayEvent> events = canonicalTerminalEvents(
                    replayProjector.projectHistory(bundle),
                    claim,
                    finalSummary,
                    detail.getRun().getErrorCode(),
                    detail.getRun().getErrorMsg()
            );
            for (int index = 0; index < events.size(); index++) {
                GptProcessResult frame = replayProjector.projectFrame(
                        claim.getRequestId(),
                        events.get(index),
                        index == events.size() - 1,
                        Constants.SUCCESS
                );
                replayFrameSink.sendReplayFrame(frame);
            }
            log.info("{} terminal Agent run replayed without execution, frameCount={}",
                    claim.getRequestId(), events.size());
            return finalSummary;
        } catch (Exception replayError) {
            log.warn("{} terminal Agent run replay failed; using canonical summary fallback errorType={}",
                    claim.getRequestId(), replayError.getClass().getSimpleName());
            emitFallback(printer, claim.getRunStatus(), fallbackSummary,
                    StringUtils.defaultIfBlank(claim.getErrorCode(), "RUN_REPLAY_FAILED"),
                    StringUtils.defaultIfBlank(claim.getErrorMsg(),
                            "The completed run could not be fully replayed."));
            return fallbackSummary;
        }
    }

    private List<ProjectedReplayEvent> canonicalTerminalEvents(List<ProjectedReplayEvent> projected,
                                                                DialogueRunClaim claim,
                                                                String summary,
                                                                String errorCode,
                                                                String errorMessage) {
        List<ProjectedReplayEvent> nonTerminal = new ArrayList<>();
        List<ProjectedReplayEvent> results = new ArrayList<>();
        ProjectedReplayEvent runFinished = null;
        if (projected != null) {
            for (ProjectedReplayEvent event : projected) {
                String messageType = messageType(event);
                if ("run_finished".equals(messageType)) {
                    if (runFinished == null) {
                        runFinished = event;
                    }
                } else if ("result".equals(messageType)) {
                    results.add(event);
                } else {
                    nonTerminal.add(event);
                }
            }
        }
        if (runFinished == null) {
            runFinished = buildTerminalEvent(
                    claim, "run_finished", summary, errorCode, errorMessage, false);
        }
        if (results.isEmpty()) {
            results.add(buildTerminalEvent(
                    claim, "result", summary, errorCode, errorMessage, true));
        }
        List<ProjectedReplayEvent> canonical = new ArrayList<>(nonTerminal.size() + results.size() + 1);
        canonical.addAll(nonTerminal);
        canonical.add(runFinished);
        canonical.addAll(results);
        return canonical;
    }

    private ProjectedReplayEvent buildTerminalEvent(DialogueRunClaim claim,
                                                     String messageType,
                                                     String summary,
                                                     String errorCode,
                                                     String errorMessage,
                                                     boolean resultEvent) {
        Map<String, Object> payload = terminalMetadata(claim.getRunStatus(), errorCode, errorMessage);
        payload.put("requestId", claim.getRequestId());
        payload.put("messageType", messageType);
        payload.put("isFinal", true);
        payload.put("finish", resultEvent);
        if (resultEvent) {
            payload.put("taskSummary", StringUtils.defaultString(summary));
            payload.put("result", StringUtils.defaultString(summary));
        }
        return ProjectedReplayEvent.builder()
                .taskId(claim.getRequestId() + ":lifecycle")
                .taskOrder(Integer.MAX_VALUE - (resultEvent ? 0 : 1))
                .messageId(claim.getRequestId() + ":" + messageType)
                .messageType("agent_event")
                .messageOrder(resultEvent ? 2 : 1)
                .resultMap(payload)
                .build();
    }

    private String messageType(ProjectedReplayEvent event) {
        if (event == null || !(event.getResultMap() instanceof Map<?, ?> resultMap)) {
            return null;
        }
        Object messageType = resultMap.get("messageType");
        return messageType == null ? null : String.valueOf(messageType);
    }

    private void emitFallback(Printer printer,
                              Integer runStatus,
                              String summary,
                              String errorCode,
                              String errorMessage) {
        if (runStatus != null && runStatus == ExecutionLedgerConstants.STATUS_SUCCESS) {
            printer.send(new AgentStreamEvent.Complete(null, StringUtils.defaultString(summary), 0L, 0L));
            return;
        }
        printer.send(new AgentStreamEvent.Error(
                null,
                StringUtils.defaultIfBlank(errorCode, "RUN_REPLAY_FAILED"),
                StringUtils.defaultIfBlank(errorMessage, "The completed run failed.")));
    }

    private Map<String, Object> terminalMetadata(Integer runStatus,
                                                 String errorCode,
                                                 String errorMessage) {
        String protocolStatus = switch (runStatus == null
                ? ExecutionLedgerConstants.STATUS_FAILED
                : runStatus) {
            case ExecutionLedgerConstants.STATUS_SUCCESS -> "SUCCESS";
            case ExecutionLedgerConstants.STATUS_TIMEOUT -> "TIMEOUT";
            case ExecutionLedgerConstants.STATUS_STOPPED -> "STOPPED";
            default -> "FAILED";
        };
        boolean completed = "SUCCESS".equals(protocolStatus);
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("status", protocolStatus);
        terminal.put("runStatus", protocolStatus);
        terminal.put("completionGatePassed", completed);
        terminal.put("stopReason", completed ? "COMPLETED"
                : StringUtils.defaultIfBlank(errorCode, "EXECUTION_ERROR"));
        if (!completed && StringUtils.isNotBlank(errorCode)) {
            terminal.put("errorCode", errorCode);
        }
        if (!completed && StringUtils.isNotBlank(errorMessage)) {
            terminal.put("errorMessage", errorMessage);
        }
        return terminal;
    }
}
