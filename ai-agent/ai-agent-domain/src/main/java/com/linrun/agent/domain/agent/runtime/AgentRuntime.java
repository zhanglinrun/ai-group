package com.linrun.agent.domain.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerRunSupport;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunClaim;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.replay.DialogueRunReplayService;
import com.linrun.agent.domain.agent.reactor.model.dto.FileInformation;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.agent.ExplicitToolChoicePolicy;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.deepresearch.DeepResearchResult;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphPort;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunRequest;
import com.linrun.agent.domain.agent.runtime.harness.AgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.harness.DefaultAgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.harness.HarnessErrorCode;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunLeaseRenewalResult;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.metrics.AgentRunMetrics;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.profile.AgentProfileResolver;
import com.linrun.agent.domain.agent.runtime.profile.ResolvedAgentProfile;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.util.DateUtil;
import com.linrun.agent.domain.agent.runtime.observability.AgentTraceMapper;
import com.linrun.agent.domain.agent.runtime.observability.AgentTraceRecorder;
import com.linrun.agent.domain.agent.runtime.observability.AgentTraceScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;

/** Composition root for one request-scoped Agent Loop run. */
@Slf4j
@Service
public class AgentRuntime {

    private final AgentToolCollectionFactory toolCollectionFactory;
    private final AgentExecutionRecorder executionRecorder;
    private final ReactorRuntimeDependencies runtimeDependencies;
    private final AgentLoopFactory agentLoopFactory;
    private final AgentProfileResolver agentProfileResolver;
    private final DialogueRunReplayService dialogueRunReplayService;
    private final GraphPort deepResearchGraphPort;
    private final AgentHarnessFacade agentHarnessFacade;
    private final AgentTraceRecorder agentTraceRecorder;

    /** Direct-call compatibility for existing domain tests and non-Spring consumers. */
    public AgentRuntime(AgentToolCollectionFactory toolCollectionFactory,
                        AgentExecutionRecorder executionRecorder,
                        ReactorRuntimeDependencies runtimeDependencies) {
        this(toolCollectionFactory, executionRecorder, runtimeDependencies,
                AgentLoopFactory.defaults(), null, new DialogueRunReplayService(null, null),
                (GraphPort) null, null, AgentTraceRecorder.noop());
    }

    /** Direct-call compatibility for tests and embedded consumers. */
    public AgentRuntime(AgentToolCollectionFactory toolCollectionFactory,
                        AgentExecutionRecorder executionRecorder,
                        ReactorRuntimeDependencies runtimeDependencies,
                        AgentLoopFactory agentLoopFactory) {
        this(toolCollectionFactory, executionRecorder, runtimeDependencies,
                agentLoopFactory, null, new DialogueRunReplayService(null, null),
                (GraphPort) null, null, AgentTraceRecorder.noop());
    }

    /** Direct-call compatibility for focused deep-research routing tests. */
    public AgentRuntime(AgentToolCollectionFactory toolCollectionFactory,
                        AgentExecutionRecorder executionRecorder,
                        ReactorRuntimeDependencies runtimeDependencies,
                        AgentLoopFactory agentLoopFactory,
                        GraphPort deepResearchGraphPort) {
        this(toolCollectionFactory, executionRecorder, runtimeDependencies,
                agentLoopFactory, null, new DialogueRunReplayService(null, null), deepResearchGraphPort, null,
                AgentTraceRecorder.noop());
    }

    /** Direct-call compatibility for tests that inject profile/replay collaborators. */
    public AgentRuntime(AgentToolCollectionFactory toolCollectionFactory,
                        AgentExecutionRecorder executionRecorder,
                        ReactorRuntimeDependencies runtimeDependencies,
                        AgentLoopFactory agentLoopFactory,
                        AgentProfileResolver agentProfileResolver,
                        DialogueRunReplayService dialogueRunReplayService) {
        this(toolCollectionFactory, executionRecorder, runtimeDependencies, agentLoopFactory,
                agentProfileResolver, dialogueRunReplayService, (GraphPort) null, null, AgentTraceRecorder.noop());
    }

    @Autowired
    public AgentRuntime(AgentToolCollectionFactory toolCollectionFactory,
                        AgentExecutionRecorder executionRecorder,
                        ReactorRuntimeDependencies runtimeDependencies,
                        AgentLoopFactory agentLoopFactory,
                        AgentProfileResolver agentProfileResolver,
                        DialogueRunReplayService dialogueRunReplayService,
                        ObjectProvider<GraphPort> deepResearchGraphPort,
                        ObjectProvider<AgentHarnessFacade> agentHarnessFacade,
                        ObjectProvider<AgentTraceRecorder> agentTraceRecorder) {
        this(toolCollectionFactory, executionRecorder, runtimeDependencies, agentLoopFactory,
                agentProfileResolver, dialogueRunReplayService,
                deepResearchGraphPort == null ? null : deepResearchGraphPort.getIfAvailable(),
                agentHarnessFacade == null ? null : agentHarnessFacade.getIfAvailable(),
                agentTraceRecorder == null ? null : agentTraceRecorder.getIfAvailable());
    }

    private AgentRuntime(AgentToolCollectionFactory toolCollectionFactory,
                         AgentExecutionRecorder executionRecorder,
                         ReactorRuntimeDependencies runtimeDependencies,
                         AgentLoopFactory agentLoopFactory,
                         AgentProfileResolver agentProfileResolver,
                         DialogueRunReplayService dialogueRunReplayService,
                         GraphPort deepResearchGraphPort,
                         AgentHarnessFacade agentHarnessFacade,
                         AgentTraceRecorder agentTraceRecorder) {
        this.toolCollectionFactory = toolCollectionFactory;
        this.executionRecorder = executionRecorder;
        this.runtimeDependencies = runtimeDependencies;
        this.agentLoopFactory = Objects.requireNonNull(agentLoopFactory, "AgentLoopFactory must not be null");
        this.agentProfileResolver = agentProfileResolver;
        this.dialogueRunReplayService = Objects.requireNonNull(
                dialogueRunReplayService, "DialogueRunReplayService must not be null");
        this.deepResearchGraphPort = deepResearchGraphPort;
        this.agentHarnessFacade = agentHarnessFacade == null
                ? new DefaultAgentHarnessFacade(this.agentLoopFactory)
                : agentHarnessFacade;
        this.agentTraceRecorder = agentTraceRecorder == null ? AgentTraceRecorder.noop() : agentTraceRecorder;
    }

    public String run(AgentRequest request, Printer printer) {
        return runWithOutcome(request, printer).answer();
    }

    public AgentRuntimeOutcome runWithOutcome(AgentRequest request, Printer printer) {
        AgentContext context = null;
        boolean ownsRunSideEffects = false;
        boolean terminalPersisted = false;
        String finalAnswer = "";
        ScheduledFuture<?> heartbeatFuture = null;
        AgentTraceScope sessionTraceScope = null;
        AgentTraceScope runTraceScope = null;
        Throwable traceError = null;
        try {
            normalizeExecutionMode(request);
            applyResolvedProfile(request);
            context = createContext(request, printer);
            DialogueRunClaim runClaim = ExecutionLedgerRunSupport.initializeRun(
                    executionRecorder,
                    context,
                    request,
                    resolveEntryAgent(request)
            );
            switch (runClaim.getDisposition()) {
                case RUNNING -> {
                    emitDuplicateRunningTerminal(printer, runClaim);
                    return AgentRuntimeOutcome.notExecuted("");
                }
                case FINISHED -> {
                    return AgentRuntimeOutcome.notExecuted(dialogueRunReplayService.replay(printer, runClaim));
                }
                case OWNER_MISMATCH -> {
                    emitRunClaimRejected(printer, "RUN_OWNER_MISMATCH",
                            "The request id belongs to another owner.");
                    return AgentRuntimeOutcome.notExecuted("");
                }
                case REQUEST_MISMATCH -> {
                    emitRunClaimRejected(printer, "RUN_REQUEST_MISMATCH",
                            "The request id was already used by another session or payload.");
                    return AgentRuntimeOutcome.notExecuted("");
                }
                case NEW -> {
                    // The durable claim is active in context; only this branch may reach tools/models.
                    ownsRunSideEffects = true;
                    context.setAgentTraceRecorder(agentTraceRecorder);
                    AgentTraceMapper traceMapper = new AgentTraceMapper();
                    sessionTraceScope = agentTraceRecorder.start("session", null, traceMapper.session(context));
                    runTraceScope = agentTraceRecorder.start("run", sessionTraceScope, traceMapper.run(context));
                    context.getAgentRunState().activateTrace(sessionTraceScope, runTraceScope);
                }
            }
            LLMSettings selectedSettings = runtimeDependencies.resolveAgentLlmSettings(context);
            context.setSelectedModelName(selectedSettings.getModel());
            heartbeatFuture = startRunHeartbeat(context);
            printer.send(new AgentStreamEvent.AgentStart(
                    context.getRequestId(), request.getOwnerId(), request.getSessionId(),
                    runtimeAgentName(request), context.getSelectedModelName()));
            emitUploadedAttachmentEvents(request, context, printer);

            ToolCollection toolCollection = toolCollectionFactory.buildForUnified(context, request);
            context.setToolCollection(toolCollection);
            List<String> activeToolNames = activeToolNames(toolCollection);
            context.setToolInvocationContract(ToolInvocationContract.resolve(
                    request.getQuery(), activeToolNames));
            ExplicitToolChoicePolicy.ExplicitToolRequirement toolRequirement =
                    ExplicitToolChoicePolicy.inspectRequiredTool(
                            request.getQuery(), 1, activeToolNames);
            if (toolRequirement.shouldFailFast()) {
                context.cancel(AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE);
                context.markRunFailed();
                Map<String, Object> capabilityDetails = new LinkedHashMap<>();
                capabilityDetails.put("requiredCapability", "TOOL");
                capabilityDetails.put("requestedToolName", toolRequirement.requestedToolName());
                capabilityDetails.put("capabilityResolution", toolRequirement.resolution().name());
                ExecutionLedgerRunSupport.finishRun(
                        context,
                        ExecutionLedgerConstants.STATUS_FAILED,
                        null,
                        AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE.name(),
                        "Explicitly required tool is unavailable: " + toolRequirement.requestedToolName()
                );
                terminalPersisted = true;
                emitTerminalFailure(
                        context,
                        printer,
                        AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE,
                        "The explicitly required tool is not available in the active tool catalog.",
                        capabilityDetails
                );
                return AgentRuntimeOutcome.executed("");
            }
            if (ExplicitToolChoicePolicy.requiresNetworkLookup(request.getQuery())
                    && (Boolean.FALSE.equals(request.getOnline()) || !hasNetworkLookupTool(toolCollection))) {
                context.cancel(AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE);
                context.markRunFailed();
                ExecutionLedgerRunSupport.finishRun(
                        context,
                        ExecutionLedgerConstants.STATUS_FAILED,
                        null,
                        AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE.name(),
                        "Required network lookup capability is unavailable"
                );
                terminalPersisted = true;
                emitTerminalFailure(
                        context,
                        printer,
                        AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE,
                        "The user required network lookup, but network access is disabled or unavailable."
                );
                return AgentRuntimeOutcome.executed("");
            }
            if (isDeepResearch(request) && deepResearchGraphPort != null) {
                AgentTraceScope graphTraceScope = agentTraceRecorder.start("graph", runTraceScope,
                        new AgentTraceMapper().graph(context));
                Throwable graphTraceError = null;
                try {
                    DeepResearchResult result = deepResearchGraphPort.start(GraphRunRequest.from(context, request)).result();
                    finalAnswer = StringUtils.defaultString(result.summary());
                    boolean completed = result.completed();
                    AgentStopReason stopReason = completed ? AgentStopReason.COMPLETED : AgentStopReason.EXECUTION_ERROR;
                    boolean failed = !"SUCCESS".equals(resolveProtocolStatus(stopReason, completed));
                    ExecutionLedgerRunSupport.finishRun(
                            context,
                            resolveLedgerStatus(stopReason, completed),
                            finalAnswer,
                            failed ? stopReason.name() : null,
                            failed ? "Deep research graph stopped before successful completion" : null
                    );
                    terminalPersisted = true;
                    emitDeepResearchFinalResult(request, context, result, completed);
                    return AgentRuntimeOutcome.executed(finalAnswer);
                } catch (RuntimeException graphError) {
                    graphTraceError = graphError;
                    throw graphError;
                } finally {
                    agentTraceRecorder.end(graphTraceScope, graphTraceError);
                }
            }
            AgentHarnessFacade.ToolLoopResult loopResult = agentHarnessFacade.runToolLoop(
                    context, AgentHarnessFacade.ToolLoopRequest.standard(request.getQuery()));
            AgentLoop agentLoop = loopResult.agentLoop();
            finalAnswer = loopResult.answer();
            boolean completed = agentLoop.getState() == AgentState.FINISHED && !context.isRunFailed();
            AgentStopReason stopReason = effectiveStopReason(agentLoop.getStopReason());
            boolean failed = !"SUCCESS".equals(resolveProtocolStatus(stopReason, completed));
            ExecutionLedgerRunSupport.finishRun(
                    context,
                    resolveLedgerStatus(stopReason, completed),
                    finalAnswer,
                    failed ? stopReason.name() : null,
                    failed ? "Agent Loop stopped before successful completion" : null
            );
            terminalPersisted = true;
            emitFinalResult(request, context, agentLoop, finalAnswer);
            return AgentRuntimeOutcome.executed(finalAnswer);
        } catch (Exception error) {
            traceError = error;
            if (terminalPersisted) {
                log.warn("{} Agent Loop terminal delivery failed after durable finalization errorType={}",
                        request == null ? null : request.getRequestId(), error.getClass().getSimpleName());
                return ownsRunSideEffects
                        ? AgentRuntimeOutcome.executed(finalAnswer)
                        : AgentRuntimeOutcome.notExecuted(finalAnswer);
            }
            if (context != null) {
                context.cancel(AgentStopReason.EXECUTION_ERROR);
                context.markRunFailed();
            }
            QuotaInsufficientException quotaFailure = quotaFailure(error);
            HarnessErrorCode harnessErrorCode = HarnessErrorCode.from(error,
                    context == null ? AgentStopReason.NONE : context.cancellationReason());
            String terminalErrorCode = quotaFailure != null
                    ? "QUOTA_INSUFFICIENT"
                    : ownsRunSideEffects && StringUtils.isNotBlank(finalAnswer)
                    ? "RUN_FINALIZATION_FAILED"
                    : harnessErrorCode.name();
            String terminalErrorMessage = quotaFailure == null
                    ? "Agent Loop execution failed."
                    : StringUtils.defaultIfBlank(quotaFailure.getMessage(), "额度不足，无法执行本次 Agent 请求。");
            Exception terminalPersistenceError = null;
            if (context != null && ownsRunSideEffects) {
                try {
                    ExecutionLedgerRunSupport.finishRun(
                            context,
                            ExecutionLedgerConstants.STATUS_FAILED,
                            StringUtils.defaultIfBlank(finalAnswer, null),
                            terminalErrorCode,
                            terminalErrorMessage
                    );
                    terminalPersisted = true;
                } catch (Exception persistenceError) {
                    terminalPersistenceError = persistenceError;
                    terminalErrorCode = "RUN_FINALIZATION_FAILED";
                    log.error("{} failed to persist canonical Agent Loop failure terminal errorType={}",
                            request == null ? null : request.getRequestId(),
                            persistenceError.getClass().getSimpleName());
                }
            }
            try {
                Map<String, Object> failureDetails = new LinkedHashMap<>();
                failureDetails.put("errorCode", terminalErrorCode);
                failureDetails.put("durableTerminalPersisted", terminalPersisted);
                if (terminalPersistenceError != null) {
                    failureDetails.put("retryable", true);
                }
                emitTerminalFailure(
                        context,
                        printer,
                        AgentStopReason.EXECUTION_ERROR,
                        terminalErrorMessage,
                        failureDetails
                );
            } catch (Exception terminalError) {
                log.error("{} failed to emit canonical Agent Loop failure terminal errorType={}",
                        request == null ? null : request.getRequestId(),
                        terminalError.getClass().getSimpleName());
            }
            log.error("{} Agent Loop execution failed errorType={}",
                    request == null ? null : request.getRequestId(), error.getClass().getSimpleName());
            return ownsRunSideEffects
                    ? AgentRuntimeOutcome.executed("")
                    : AgentRuntimeOutcome.notExecuted("");
        } finally {
            cancelRunHeartbeat(heartbeatFuture);
            Throwable terminalTraceError = traceError;
            if (terminalTraceError == null && context != null && context.isRunFailed()) {
                terminalTraceError = new IllegalStateException("agent_run_failed");
            }
            agentTraceRecorder.end(runTraceScope, terminalTraceError);
            agentTraceRecorder.end(sessionTraceScope, terminalTraceError);
            if (context != null && runtimeDependencies != null
                    && runtimeDependencies.getApprovalGate() != null) {
                runtimeDependencies.getApprovalGate().clearRunCache(context.getRequestId());
            }
        }
    }

    private ScheduledFuture<?> startRunHeartbeat(AgentContext context) {
        if (context == null || !context.hasActiveLedgerRun()
                || executionRecorder == null || runtimeDependencies == null
                || runtimeDependencies.getHeartbeatScheduler() == null) {
            return null;
        }
        long intervalMillis = runtimeDependencies.effectiveRunHeartbeatIntervalMillis();
        Long runId = context.getAgentRunState().getRunId();
        String requestId = context.getRequestId();
        try {
            return runtimeDependencies.getHeartbeatScheduler().scheduleAtFixedRate(() -> {
                try {
                    LocalDateTime heartbeatAt = LocalDateTime.now();
                    DialogueRunLeaseRenewalResult lease = executionRecorder.renewRunLease(
                            runId,
                            requestId,
                            context.getRunOwnerWorkerId(),
                            context.getFencingToken() == null ? 0L : context.getFencingToken(),
                            heartbeatAt,
                            heartbeatAt.plus(Duration.ofMillis(Math.max(30_000L, intervalMillis * 3L)))
                    );
                    if (!lease.isActive()) {
                        AgentStopReason reason = lease.status() == DialogueRunLeaseRenewalResult.Status.CANCEL_REQUESTED
                                ? AgentStopReason.RUN_CANCELLED
                                : AgentStopReason.RUN_OWNERSHIP_LOST;
                        context.cancel(reason);
                        log.warn("{} durable run lease no longer active status={}", requestId, lease.status());
                    }
                } catch (Exception heartbeatError) {
                    log.warn("{} durable run heartbeat failed errorType={}",
                            requestId, heartbeatError.getClass().getSimpleName());
                }
            }, Instant.now().plusMillis(intervalMillis), Duration.ofMillis(intervalMillis));
        } catch (Exception scheduleError) {
            log.warn("{} durable run heartbeat scheduling failed errorType={}",
                    requestId, scheduleError.getClass().getSimpleName());
            return null;
        }
    }

    private void cancelRunHeartbeat(ScheduledFuture<?> heartbeatFuture) {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
    }

    private void emitDuplicateRunningTerminal(Printer printer, DialogueRunClaim claim) {
        printer.send(new AgentStreamEvent.Error(
                claim.getRequestId(), "RUN_ALREADY_IN_PROGRESS",
                "This request is already running. Retry after the active run reaches a terminal state."));
    }

    private void emitRunClaimRejected(Printer printer, String stopReason, String errorMessage) {
        printer.send(new AgentStreamEvent.Error(null, stopReason, errorMessage));
    }

    /**
     * Replays the server-authorized attachment list into the durable Run stream.  The payload
     * deliberately contains only metadata and a stable artifact reference — never a storage URL
     * or file content — so reconnecting clients can render upload progress without widening file
     * access.
     */
    private void emitUploadedAttachmentEvents(AgentRequest request, AgentContext context, Printer printer) {
        if (request == null || context == null || printer == null
                || request.getSessionFiles() == null || request.getSessionFiles().isEmpty()) {
            return;
        }
        for (FileInformation file : request.getSessionFiles()) {
            if (file == null || StringUtils.isBlank(file.getFileName())) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "FILE_UPLOADED");
            payload.put("fileName", file.getFileName());
            payload.put("mimeType", StringUtils.defaultString(file.getMimeType()));
            payload.put("fileSize", file.getFileSize() == null ? 0 : file.getFileSize());
            payload.put("artifactHash", StringUtils.defaultString(file.getArtifactHash()));
            String artifactReference = StringUtils.defaultIfBlank(file.getResourceKey(), file.getFileName());
            printer.send(new AgentStreamEvent.StageOutput(
                    context.getRequestId(), null, "file_upload", payload,
                    List.of(Map.of("artifactReference", artifactReference)), true));
        }
    }

    private void applyResolvedProfile(AgentRequest request) {
        if (request == null || agentProfileResolver == null || StringUtils.isBlank(request.getAiAgentId())) return;
        ResolvedAgentProfile profile = agentProfileResolver.resolve(request.getAiAgentId());
        if (profile == null) return;
        request.setResolvedRoleName(profile.agentName());
        request.setProfileClientIds(profile.clientIds());
        String base = StringUtils.defaultString(request.getBasePrompt()).stripTrailing();
        request.setBasePrompt(base + profile.trustedPrompt());
    }

    private void normalizeExecutionMode(AgentRequest request) {
        if (request != null && StringUtils.equalsIgnoreCase(request.getExecutionMode(), "AUTO")) {
            request.setExecutionMode("STANDARD");
        }
    }

    private AgentContext createContext(AgentRequest request, Printer printer) {
        return AgentContext.builder()
                .requestId(request.getRequestId())
                .sessionId(request.getSessionId())
                .ownerId(parseOwnerId(request.getOwnerId()))
                .printer(printer)
                .query(request.getQuery())
                .task("")
                .dateInfo(DateUtil.CurrentDateInfo())
                .productFiles(new ArrayList<>(convertFiles(request.getSessionFiles())))
                .taskProductFiles(new ArrayList<>())
                .basePrompt(request.getBasePrompt())
                .historyDialogue(request.getHistoryDialogue())
                .agentType(request.getAgentType())
                .executionProfile(request.resolveExecutionProfile())
                .outputStyle(request.getOutputStyle())
                .isStream(Objects.requireNonNullElse(request.getIsStream(), false))
                .online(request.getOnline())
                .templateType("empty")
                .modelIdOverride(request.getModelId())
                .runStartedAtMillis(System.currentTimeMillis())
                .executionRecorder(executionRecorder)
                .agentTraceRecorder(agentTraceRecorder)
                .runtimeDependencies(runtimeDependencies)
                .build();
    }

    private Long parseOwnerId(String ownerId) {
        if (StringUtils.isBlank(ownerId)) {
            return null;
        }
        return Long.valueOf(ownerId);
    }

    private void emitTerminalFailure(AgentContext context,
                                     Printer printer,
                                     AgentStopReason stopReason,
                                     String errorMessage) {
        emitTerminalFailure(context, printer, stopReason, errorMessage, Map.of());
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

    private void emitTerminalFailure(AgentContext context,
                                     Printer printer,
                                     AgentStopReason stopReason,
                                     String errorMessage,
                                     Map<String, Object> details) {
        if (printer == null || printer.isAborted()) {
            return;
        }
        AgentStopReason effectiveReason = stopReason == null
                ? AgentStopReason.EXECUTION_ERROR
                : stopReason;
        if (details != null && !details.isEmpty()) {
            printer.send(new AgentStreamEvent.StageOutput(
                    context == null ? null : context.getRequestId(), null, "failure_details",
                    details, List.of(), true));
        }
        String errorCode = details == null || details.get("errorCode") == null
                ? effectiveReason.name()
                : String.valueOf(details.get("errorCode"));
        printer.send(new AgentStreamEvent.Error(
                context == null ? null : context.getRequestId(), errorCode,
                StringUtils.defaultIfBlank(errorMessage, "Agent Loop execution failed.")));
    }

    private boolean hasNetworkLookupTool(ToolCollection toolCollection) {
        return ExplicitToolChoicePolicy.hasNetworkLookupTool(activeToolNames(toolCollection));
    }

    private List<String> activeToolNames(ToolCollection toolCollection) {
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

    private void emitFinalResult(AgentRequest request,
                                 AgentContext context,
                                 AgentLoop agentLoop,
                                 String answer) {
        String finalAnswer = StringUtils.defaultString(answer);
        boolean completed = agentLoop.getState() == AgentState.FINISHED && !context.isRunFailed();
        AgentStopReason stopReason = effectiveStopReason(agentLoop.getStopReason());
        String terminalStatus = resolveProtocolStatus(stopReason, completed);

        Map<String, Object> metrics = AgentRunMetrics.fromContext(
                context,
                runtimeDependencies.requireReactorConfig().getAgentLoopModelName());

        if (completed) {
            context.getPrinter().send(new AgentStreamEvent.Complete(
                    context.getRequestId(), finalAnswer,
                    numberValue(metrics.get(AgentRunMetrics.DURATION_MS)),
                    numberValue(metrics.get(AgentRunMetrics.CHARGED_MICROCREDITS))));
        } else {
            context.getPrinter().send(new AgentStreamEvent.Error(
                    context.getRequestId(), stopReason.name(),
                    StringUtils.defaultIfBlank(finalAnswer, "Agent Loop execution failed.")));
        }
        log.info("{} Agent Loop finished status={} resultChars={}",
                request.getRequestId(), terminalStatus, finalAnswer.length());
    }

    private void emitDeepResearchFinalResult(AgentRequest request,
                                             AgentContext context,
                                             DeepResearchResult result,
                                             boolean completed) {
        String finalAnswer = StringUtils.defaultString(result.summary());
        Map<String, Object> metrics = AgentRunMetrics.fromContext(
                context,
                runtimeDependencies.requireReactorConfig().getAgentLoopModelName());
        if (completed) {
            context.getPrinter().send(new AgentStreamEvent.Complete(
                    context.getRequestId(), finalAnswer,
                    numberValue(metrics.get(AgentRunMetrics.DURATION_MS)),
                    numberValue(metrics.get(AgentRunMetrics.CHARGED_MICROCREDITS))));
        } else {
            context.getPrinter().send(new AgentStreamEvent.Error(
                    context.getRequestId(), AgentStopReason.EXECUTION_ERROR.name(),
                    StringUtils.defaultIfBlank(finalAnswer, "Deep research graph execution failed.")));
        }
        log.info("{} Deep research graph finished status={} quality={} sourceCount={} chars={}",
                request.getRequestId(),
                completed ? "SUCCESS" : "FAILED",
                result.qualityStatus(),
                result.sourceCount(),
                result.charCount());
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private AgentStopReason effectiveStopReason(AgentStopReason stopReason) {
        return stopReason == null ? AgentStopReason.EXECUTION_ERROR : stopReason;
    }

    static String resolveProtocolStatus(AgentStopReason stopReason, boolean completed) {
        if (completed) {
            return "SUCCESS";
        }
        AgentStopReason effective = stopReason == null ? AgentStopReason.EXECUTION_ERROR : stopReason;
        return switch (effective) {
            case TIME_BUDGET -> "TIMEOUT";
            case DOWNSTREAM_ABORTED, RUN_CANCELLED -> "STOPPED";
            default -> "FAILED";
        };
    }

    static int resolveLedgerStatus(AgentStopReason stopReason, boolean completed) {
        if (completed) {
            return ExecutionLedgerConstants.STATUS_SUCCESS;
        }
        AgentStopReason effective = stopReason == null ? AgentStopReason.EXECUTION_ERROR : stopReason;
        return switch (effective) {
            case TIME_BUDGET -> ExecutionLedgerConstants.STATUS_TIMEOUT;
            case DOWNSTREAM_ABORTED, RUN_CANCELLED -> ExecutionLedgerConstants.STATUS_STOPPED;
            default -> ExecutionLedgerConstants.STATUS_FAILED;
        };
    }

    private String resolveEntryAgent(AgentRequest request) {
        String mode = StringUtils.defaultString(request.getExecutionMode(), "standard")
                .trim()
                .toLowerCase();
        return switch (mode) {
            case "deep" -> ExecutionLedgerConstants.ENTRY_AGENT_LOOP_DEEP;
            default -> ExecutionLedgerConstants.ENTRY_AGENT_LOOP_STANDARD;
        };
    }

    private boolean isDeepResearch(AgentRequest request) {
        return request != null && StringUtils.equalsIgnoreCase(request.getExecutionMode(), "DEEP");
    }

    private String runtimeAgentName(AgentRequest request) {
        return isDeepResearch(request) && deepResearchGraphPort != null
                ? ExecutionLedgerConstants.AGENT_NAME_DEEP_RESEARCH_GRAPH
                : ExecutionLedgerConstants.AGENT_NAME_AGENT_LOOP;
    }

    private List<File> convertFiles(List<FileInformation> sessionFiles) {
        if (sessionFiles == null || sessionFiles.isEmpty()) {
            return List.of();
        }
        List<File> files = new ArrayList<>(sessionFiles.size());
        for (FileInformation sessionFile : sessionFiles) {
            if (sessionFile == null) {
                continue;
            }
            files.add(File.builder()
                    .fileName(sessionFile.getFileName())
                    .description(sessionFile.getFileDesc())
                    .ossUrl(sessionFile.getOssUrl())
                    .domainUrl(sessionFile.getDomainUrl())
                    .fileSize(sessionFile.getFileSize())
                    .originFileName(sessionFile.getOriginFileName())
                    .originOssUrl(sessionFile.getOriginOssUrl())
                    .originDomainUrl(sessionFile.getOriginDomainUrl())
                    .isInternalFile(Boolean.FALSE)
                    .build());
        }
        return files;
    }
}
