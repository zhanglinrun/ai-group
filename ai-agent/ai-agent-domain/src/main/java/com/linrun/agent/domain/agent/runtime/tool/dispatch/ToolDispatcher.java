package com.linrun.agent.domain.agent.runtime.tool.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.ledger.model.ArtifactRecordCommand;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationFinishRecord;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactFormatter;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.completion.ToolExecutionEvidence;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.executor.AgentExecutorSupport;
import com.linrun.agent.domain.agent.runtime.harness.AgentFutureWaiter;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.harness.DefaultPermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.HookBus;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.RetryPolicy;
import com.linrun.agent.domain.agent.runtime.hitl.ApprovalGate;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.common.ExecuteExtraTool;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;
import com.linrun.agent.domain.agent.runtime.work.TodoStepEvidenceScope;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/**
 * One run-local tool execution pipeline. It owns validation, bounded retry,
 * dispatch, cancellation, events, evidence, artifacts and ledger consistency.
 */
@Slf4j
public final class ToolDispatcher {

    private static final long APPROVAL_THRESHOLD_MICROCREDITS = 200_000L;

    private final Host host;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolInputSchemaValidator inputSchemaValidator = new ToolInputSchemaValidator();
    private final ToolOperationLedger operationLedger = new ToolOperationLedger();
    private final Map<String, ToolExecutionOutcome> proxyResolutionFailures = new ConcurrentHashMap<>();
    private final Set<String> proxiedToolCallIds = ConcurrentHashMap.newKeySet();
    private final Map<String, TodoStepEvidenceScope> todoEvidenceScopes = new ConcurrentHashMap<>();
    private int reservedToolCallCount;

    public ToolDispatcher(Host host) {
        this.host = host;
    }

    public void reset() {
        reservedToolCallCount = 0;
        operationLedger.reset();
        proxyResolutionFailures.clear();
        proxiedToolCallIds.clear();
        todoEvidenceScopes.clear();
    }

    public ToolExecutionOutcome dispatch(ToolCall command) {
        List<ToolCall> commands = command == null ? List.of() : List.of(command);
        validateToolCallIds(commands);
        resolveDeferredProxyCalls(commands);
        normalizeToolCallNames(commands);
        captureTodoEvidenceScopes(commands);
        if (!reserveToolCallBudget(commands.size())) {
            return toolBudgetFailure();
        }
        Map<String, Long> toolInvocationIds = ensureToolInvocationIds(commands);
        AgentContext context = context();
        if (context != null && context.getAgentRunState() != null && !toolInvocationIds.isEmpty()) {
            context.getAgentRunState().bindToolInvocationIds(toolInvocationIds);
        }
        Map<String, Integer> dispatchIndexMapping = buildDispatchIndexMapping(commands);
        emitToolCallRunningEvents(commands, dispatchIndexMapping);
        ToolExecutionOutcome outcome = finalizeToolExecutionOutcome(command, executeToolInternal(command));
        operationLedger.recordSuccessful(command, outcome);
        recordToolExecutionEvidence(command, outcome);
        finishToolInvocation(command, outcome);
        recordToolArtifacts(command);
        emitToolCallFinishedEvent(command, dispatchIndexMapping.get(command == null ? null : command.getId()), outcome);
        return outcome;
    }

    public Map<String, ToolExecutionOutcome> dispatch(List<ToolCall> commands) {
        Map<String, ToolExecutionOutcome> result = new ConcurrentHashMap<>();
        if (commands == null || commands.isEmpty()) {
            return result;
        }
        validateToolCallIds(commands);
        resolveDeferredProxyCalls(commands);
        normalizeToolCallNames(commands);
        captureTodoEvidenceScopes(commands);
        if (!reserveToolCallBudget(commands.size())) {
            Map<String, ToolExecutionOutcome> rejected = new LinkedHashMap<>();
            for (ToolCall command : commands) {
                if (command != null && StringUtils.isNotBlank(command.getId())) {
                    rejected.put(command.getId(), toolBudgetFailure());
                }
            }
            return rejected;
        }

        Map<String, Integer> dispatchIndexMapping = buildDispatchIndexMapping(commands);
        Map<String, Long> toolInvocationIds = ensureToolInvocationIds(commands);
        AgentContext context = context();
        if (context != null && context.getAgentRunState() != null) {
            context.getAgentRunState().bindToolInvocationIds(toolInvocationIds);
        }
        emitToolCallRunningEvents(commands, dispatchIndexMapping);

        AgentStopReason waitStopReason = dispatchInSafetyBlocks(
                commands, result, dispatchIndexMapping);
        if (waitStopReason != AgentStopReason.NONE) {
            host.stop(waitStopReason);
        }

        for (ToolCall command : commands) {
            if (command == null || StringUtils.isBlank(command.getId()) || result.containsKey(command.getId())) {
                continue;
            }
            ToolExecutionOutcome failure = waitStopReason == AgentStopReason.TIME_BUDGET
                    ? toolTimeBudgetFailure()
                    : waitStopReason == AgentStopReason.DOWNSTREAM_ABORTED
                    ? toolDownstreamAbortedFailure()
                    : toolExecutionFailure();
            completeToolCallOnce(result, command, dispatchIndexMapping, failure);
        }

        Map<String, ToolExecutionOutcome> ordered = new LinkedHashMap<>(commands.size());
        for (ToolCall command : commands) {
            if (command != null && StringUtils.isNotBlank(command.getId())) {
                ordered.put(command.getId(), result.get(command.getId()));
            }
        }
        return ordered;
    }

    private void normalizeToolCallNames(List<ToolCall> commands) {
        ToolCollection tools = executionTools();
        if (tools == null || commands == null || commands.isEmpty()) {
            return;
        }
        for (ToolCall command : commands) {
            if (command == null || command.getFunction() == null) {
                continue;
            }
            String requestedName = command.getFunction().getName();
            String resolvedName = tools.resolveActiveToolName(requestedName);
            if (!StringUtils.equals(requestedName, resolvedName)) {
                command.getFunction().setName(resolvedName);
                log.info("{} normalized unique MCP tool alias requested={} exposed={}",
                        requestId(), requestedName, resolvedName);
            }
        }
    }

    /**
     * Resolve the fixed execute_extra_tool wrapper into the real deferred MCP
     * command before ledger/event/evidence registration. The provider-issued
     * outer toolCallId is retained while every Harness decision below this
     * boundary observes the canonical target name and native arguments.
     */
    private void resolveDeferredProxyCalls(List<ToolCall> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (ToolCall command : commands) {
            if (command == null || command.getFunction() == null
                    || !ExecuteExtraTool.NAME.equals(command.getFunction().getName())) {
                continue;
            }
            ToolExecutionOutcome failure = resolveDeferredProxyCall(command);
            if (failure != null && StringUtils.isNotBlank(command.getId())) {
                proxyResolutionFailures.put(command.getId(), failure);
            }
        }
    }

    private ToolExecutionOutcome resolveDeferredProxyCall(ToolCall command) {
        ToolCollection activeTools = executionTools();
        if (activeTools == null || activeTools.getTool(ExecuteExtraTool.NAME) == null) {
            return deferredProxyFailure(
                    "execute_extra_tool is not exposed or allowed for the current turn.");
        }

        Object wrapperInput;
        try {
            wrapperInput = parseToolArguments(command.getFunction().getArguments());
        } catch (Exception parseError) {
            return deferredProxyFailure(
                    "execute_extra_tool arguments must be a valid JSON object with tool_name and params.");
        }
        BaseTool proxyTool = activeTools.getTool(ExecuteExtraTool.NAME);
        ToolInputSchemaValidator.ValidationResult wrapperValidation =
                inputSchemaValidator.validate(proxyTool.toParams(), wrapperInput);
        if (!wrapperValidation.valid()) {
            return toolInputValidationFailure(ExecuteExtraTool.NAME, wrapperValidation.message());
        }
        if (!(wrapperInput instanceof Map<?, ?> wrapper)) {
            return deferredProxyFailure("execute_extra_tool input must be an object.");
        }

        String requestedTarget = StringUtils.trimToEmpty(String.valueOf(wrapper.get("tool_name")));
        if (requestedTarget.isEmpty()) {
            return deferredProxyFailure("execute_extra_tool requires a non-empty tool_name.");
        }
        if (ExecuteExtraTool.NAME.equals(requestedTarget)) {
            return deferredProxyFailure("execute_extra_tool cannot recursively invoke itself.");
        }

        ToolCollection catalog = toolCatalog();
        if (catalog == null) {
            return deferredProxyFailure("Deferred tool catalog is unavailable.");
        }
        String canonicalTarget = catalog.resolveActiveToolName(requestedTarget);
        McpToolInfo targetTool = catalog.getMcpTool(canonicalTarget);
        if (targetTool == null) {
            if (catalog.getTool(requestedTarget) != null) {
                return deferredProxyFailure(
                        "Tool " + requestedTarget + " is a core tool and must be called directly.");
            }
            return deferredProxyFailure(
                    "Deferred tool " + requestedTarget + " was not found in the run-local catalog.");
        }
        if (activeTools.getMcpToolMap().containsKey(canonicalTarget)) {
            return deferredProxyFailure(
                    "Tool " + canonicalTarget + " is already visible and must be called directly.");
        }

        Object targetParams = wrapper.get("params");
        command.getFunction().setName(canonicalTarget);
        try {
            command.getFunction().setArguments(objectMapper.writeValueAsString(targetParams));
        } catch (Exception serializationError) {
            return deferredProxyFailure(
                    "execute_extra_tool params could not be serialized for " + canonicalTarget + ".");
        }
        proxiedToolCallIds.add(command.getId());

        if (!isDeferredTargetAuthorized(canonicalTarget)) {
            return deferredProxyFailure(
                    "Deferred tool " + canonicalTarget
                            + " has not been discovered or explicitly required."
                            + " Call tool_search first, then retry through execute_extra_tool.");
        }
        return null;
    }

    private boolean isDeferredTargetAuthorized(String canonicalTarget) {
        AgentContext context = context();
        if (context == null) {
            return false;
        }
        if (context.getAgentRunState() != null
                && context.getAgentRunState().discoveredToolNamesSnapshot().contains(canonicalTarget)) {
            return true;
        }
        return context.getToolInvocationContract() != null
                && context.getToolInvocationContract().requiredToolNames().contains(canonicalTarget);
    }

    private ToolExecutionOutcome deferredProxyFailure(String message) {
        String detail = StringUtils.defaultIfBlank(message, "Deferred tool proxy rejected the call.");
        return ToolExecutionOutcome.failure(detail, detail, null, detail);
    }

    private AgentStopReason dispatchInSafetyBlocks(List<ToolCall> commands,
                                                   Map<String, ToolExecutionOutcome> result,
                                                   Map<String, Integer> dispatchIndexMapping) {
        int index = 0;
        while (index < commands.size()) {
            boolean concurrent = isConcurrencySafe(commands.get(index));
            int end = index + 1;
            if (concurrent) {
                while (end < commands.size() && isConcurrencySafe(commands.get(end))) {
                    end++;
                }
            }
            AgentStopReason stopReason = executeBlock(
                    commands.subList(index, end), result, dispatchIndexMapping);
            if (stopReason != AgentStopReason.NONE) {
                return stopReason;
            }
            index = end;
        }
        return AgentStopReason.NONE;
    }

    private AgentStopReason executeBlock(List<ToolCall> block,
                                         Map<String, ToolExecutionOutcome> result,
                                         Map<String, Integer> dispatchIndexMapping) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(block.size());
        Executor toolExecutor = resolveToolExecutor();
        for (ToolCall toolCall : block) {
            CompletableFuture<Void> future = AgentExecutorSupport
                    .supplyAsync(toolExecutor, "toolBatch", () -> {
                        ToolExecutionOutcome outcome = finalizeToolExecutionOutcome(
                                toolCall, executeToolInternal(toolCall));
                        AgentContext currentContext = context();
                        if (currentContext != null && currentContext.isRunDeadlineExceeded()) {
                            outcome = toolTimeBudgetFailure();
                        } else if (host.isDownstreamAborted()) {
                            outcome = toolDownstreamAbortedFailure();
                        }
                        completeToolCallOnce(result, toolCall, dispatchIndexMapping, outcome);
                        return null;
                    });
            futures.add(future);
        }

        CompletableFuture<Void> blockFuture = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        try {
            AgentFutureWaiter.await(blockFuture, context(), host.remainingRunDuration());
            return AgentStopReason.NONE;
        } catch (AgentFutureWaiter.DownstreamAbortedException abortedException) {
            cancelBlock(futures, blockFuture);
            return AgentStopReason.DOWNSTREAM_ABORTED;
        } catch (AgentFutureWaiter.RunCancelledException cancelledException) {
            cancelBlock(futures, blockFuture);
            return cancelledException.getStopReason();
        } catch (TimeoutException timeoutException) {
            cancelBlock(futures, blockFuture);
            return AgentStopReason.TIME_BUDGET;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            cancelBlock(futures, blockFuture);
            return host.isDownstreamAborted()
                    ? AgentStopReason.DOWNSTREAM_ABORTED
                    : AgentStopReason.EXECUTION_ERROR;
        } catch (ExecutionException executionException) {
            log.error("{} tool batch failed errorType={}",
                    requestId(),
                    executionException.getCause() == null
                            ? executionException.getClass().getSimpleName()
                            : executionException.getCause().getClass().getSimpleName());
            return AgentStopReason.NONE;
        }
    }

    private void cancelBlock(List<CompletableFuture<Void>> futures,
                             CompletableFuture<Void> blockFuture) {
        futures.forEach(future -> future.cancel(true));
        blockFuture.cancel(true);
    }

    private boolean isConcurrencySafe(ToolCall command) {
        if (command == null || command.getFunction() == null
                || StringUtils.isBlank(command.getFunction().getName())) {
            return false;
        }
        if (proxiedToolCallIds.contains(command.getId())) {
            return false;
        }
        ToolCollection tools = executionTools();
        if (tools == null) {
            return false;
        }
        BaseTool tool = tools.getTool(command.getFunction().getName());
        if (tool == null) {
            return false;
        }
        try {
            Object input = parseToolArguments(command.getFunction().getArguments());
            if (!validateToolInput(command.getFunction().getName(), input).valid()) {
                return false;
            }
            return tool.isConcurrencySafe(input);
        } catch (Exception ignored) {
            return false;
        }
    }

    private ToolExecutionOutcome executeToolInternal(ToolCall command) {
        return executeToolInternal(command, false);
    }

    private ToolExecutionOutcome executeToolInternal(ToolCall command, boolean approvalGranted) {
        if (command == null || command.getFunction() == null
                || StringUtils.isBlank(command.getFunction().getName())) {
            return ToolExecutionOutcome.failure(
                    "Error: Invalid function call format",
                    "Error: Invalid function call format",
                    null,
                    "Invalid function call format"
            );
        }

        ToolExecutionOutcome proxyFailure = proxyResolutionFailures.get(command.getId());
        if (proxyFailure != null) {
            return proxyFailure;
        }

        String toolName = command.getFunction().getName();
        Object args;
        try {
            args = parseToolArguments(command.getFunction().getArguments());
        } catch (Exception parseEx) {
            String rawArguments = command.getFunction().getArguments();
            log.warn("{} tool arguments parse failed tool={} argsChars={} errorType={}",
                    requestId(), toolName,
                    rawArguments == null ? 0 : rawArguments.length(), parseEx.getClass().getSimpleName());
            String message = "工具 " + toolName + " 参数解析失败：入参必须为合法 JSON，请检查后用正确参数重试。";
            return ToolExecutionOutcome.inputFailure(
                    message, message, "invalid tool arguments: " + parseEx.getMessage());
        }

        ToolInputSchemaValidator.ValidationResult schemaValidation = validateToolInput(toolName, args);
        if (!schemaValidation.valid()) {
            return toolInputValidationFailure(toolName, schemaValidation.message());
        }

        AgentContext context = context();
        ToolCollection authorizationView = permissionTools(command, toolName);
        if (!containsTool(authorizationView, toolName)) {
            String message = "Tool " + toolName
                    + " is not exposed or allowed for the current turn.";
            return ToolExecutionOutcome.failure(message, message, null, message);
        }
        PermissionPolicy permissionPolicy = host.permissionPolicy() == null
                ? new DefaultPermissionPolicy()
                : host.permissionPolicy();
        PermissionPolicy.PermissionDecision permission = permissionPolicy.evaluate(
                toolName, args, authorizationView, context);
        if (permission.decision() == PermissionPolicy.Decision.DENY) {
            String message = StringUtils.defaultIfBlank(
                    permission.reason(), "Tool permission denied.");
            return ToolExecutionOutcome.failure(message, message, null, message);
        }
        HookBus hookBus = host.hookBus() == null ? new HookBus() : host.hookBus();
        HookBus.HookDecision hookDecision = hookBus.fire(new HookBus.HookEvent(
                HookBus.HookPoint.PRE_TOOL, context, toolName, args, null));
        if (!hookDecision.allowed()) {
            String message = StringUtils.defaultIfBlank(
                    hookDecision.reason(), "Tool call blocked by PreTool hook.");
            return ToolExecutionOutcome.failure(message, message, null, message);
        }
        ToolExecutionOutcome reused = operationLedger.reuseSuccessful(
                command, allowRepeatedSuccessfulCall(toolName));
        if (reused != null) {
            log.info("{} reused successful tool operation tool={} operationKey={}",
                    requestId(), toolName, operationLedger.operationKey(command));
            return reused;
        }
        ToolExecutionOutcome approvalOutcome = applyApproval(
                command, toolName, permission, approvalGranted);
        if (approvalOutcome != null) {
            return approvalOutcome;
        }
        if (toolName.equals(host.singleUseToolName())
                && context != null
                && context.getAgentRunState() != null
                && !context.getAgentRunState().tryConsumeSingleUseTool(toolName)) {
            String message = "Tool " + toolName
                    + " was limited by the user to one invocation and has already been called in this run.";
            return ToolExecutionOutcome.failure(message, message, null, message);
        }

        RetryPolicy retryPolicy = host.retryPolicy() == null ? RetryPolicy.noRetry() : host.retryPolicy();
        int attempts = retryPolicy.maxAttempts();
        BaseTool selectedTool = toolCatalog() == null ? null : toolCatalog().getTool(toolName);
        Exception lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long attemptStartedAt = System.nanoTime();
            try {
                Object resultObject = executeSelectedTool(command, toolName, args);
                log.info("{} execute tool completed tool={} attempt={} resultType={} durationMs={}",
                        requestId(), toolName, attempt,
                        resultObject == null ? "null" : resultObject.getClass().getSimpleName(),
                        (System.nanoTime() - attemptStartedAt) / 1_000_000L);

                if (resultObject == null) {
                    return ToolExecutionOutcome.failure(
                            "Tool " + toolName + " Error.",
                            "Tool " + toolName + " Error.",
                            null,
                            "Tool returned null"
                    );
                }

                ToolResultPayload payload = normalizeToolResultPayload(resultObject);
                String toolResult = StringUtils.defaultString(payload.getToolResult());
                String llmObservation = StringUtils.defaultIfBlank(payload.getLlmObservation(), toolResult);
                if (Boolean.TRUE.equals(payload.getFailed())) {
                    return ToolExecutionOutcome.failure(
                            toolResult,
                            llmObservation,
                            payload.getStructuredOutput(),
                            StringUtils.defaultIfBlank(payload.getErrorMsg(), toolResult)
                    );
                }
                return ToolExecutionOutcome.success(toolResult, llmObservation, payload.getStructuredOutput());
            } catch (Exception error) {
                lastError = error;
                if (retryPolicy.shouldRetry(selectedTool, attempt, context)) {
                    log.warn("{} execute tool failed tool={} attempt={}/{} retrying errorType={} durationMs={}",
                            requestId(), toolName, attempt, attempts,
                            error.getClass().getSimpleName(),
                            (System.nanoTime() - attemptStartedAt) / 1_000_000L);
                    continue;
                }
                log.error("{} execute tool failed tool={} attempt={}/{} errorType={} durationMs={}",
                        requestId(), toolName, attempt, attempts, error.getClass().getSimpleName(),
                        (System.nanoTime() - attemptStartedAt) / 1_000_000L);
                break;
            }
        }
        return ToolExecutionOutcome.failure(
                "Tool " + toolName + " Error.",
                "Tool " + toolName + " Error.",
                null,
                lastError == null ? "tool failed" : lastError.getMessage()
        );
    }

    private ToolExecutionOutcome applyApproval(ToolCall command,
                                               String toolName,
                                               PermissionPolicy.PermissionDecision permission,
                                               boolean approvalGranted) {
        if (approvalGranted) {
            return null;
        }
        AgentContext context = context();
        Long ownerId = context == null ? null : context.getOwnerId();
        long estimatedMicrocredits = estimatedMicrocredits(toolName, context);
        boolean permissionRequiresApproval = permission != null && permission.requiresApproval();
        boolean costRequiresApproval = ownerId != null
                && ownerId > 0L
                && estimatedMicrocredits >= APPROVAL_THRESHOLD_MICROCREDITS;
        if (!permissionRequiresApproval && !costRequiresApproval) {
            return null;
        }
        if (ownerId == null || ownerId <= 0L) {
            String message = "Tool " + toolName + " requires an authenticated online approval.";
            return ToolExecutionOutcome.failure(message, message, null, message);
        }
        ApprovalGate gate = context.getRuntimeDependencies() == null
                ? null
                : context.getRuntimeDependencies().getApprovalGate();
        if (gate == null) {
            String message = "Tool " + toolName + " approval service is unavailable.";
            return ToolExecutionOutcome.failure(message, message, null, message);
        }

        ApprovalGate.ApprovalResult approval = gate.awaitApproval(
                ApprovalGate.ApprovalRequest.builder()
                        .runId(requestId())
                        .ownerId(String.valueOf(ownerId))
                        .toolCallId(command.getId())
                        .toolName(toolName)
                        .argumentsJson(command.getFunction().getArguments())
                        .estimatedMicrocredits(estimatedMicrocredits)
                        .approvalRequired(true)
                        .build(),
                pending -> emitPaused(pending),
                host::isDownstreamAborted);

        if (approval.getApprovalId() != null) {
            Printer printer = host.printer();
            if (printer != null) {
                printer.send(new AgentStreamEvent.ResumeStart(
                        requestId(), String.valueOf(approval.getApprovalId()), command.getId(),
                        approval.getDecision() == null ? "REJECTED" : approval.getDecision().name()));
            }
        }
        if (approval.isApproved()) {
            return null;
        }
        if (approval.isModified()) {
            ToolCall modified = ToolCall.builder()
                    .id(command.getId())
                    .type(command.getType())
                    .function(ToolCall.Function.builder()
                            .name(toolName)
                            .arguments(approval.getModifiedArguments())
                            .build())
                    .build();
            return executeToolInternal(modified, true);
        }
        if (approval.isSkipped()) {
            String message = "Tool " + toolName + " was skipped by the user.";
            return ToolExecutionOutcome.success(message, message, null);
        }
        String message = "Tool " + toolName + " was not approved: "
                + StringUtils.defaultIfBlank(approval.getReason(), "approval denied");
        return ToolExecutionOutcome.failure(message, message, null, message);
    }

    private void emitPaused(com.linrun.agent.domain.agent.runtime.hitl.ToolApproval approval) {
        Printer printer = host.printer();
        if (printer == null) {
            return;
        }
        Object preview = approval.getArgumentsPreview();
        try {
            preview = objectMapper.readTree(approval.getArgumentsPreview());
        } catch (Exception ignored) {
            // Repository always stores a redacted value; a string fallback is still safe.
        }
        printer.send(new AgentStreamEvent.Paused(
                requestId(), String.valueOf(approval.getId()), approval.getToolCallId(),
                approval.getToolName(), preview, approval.getEstimatedMicrocredits(),
                approval.getExpiresAt().toString()));
    }

    private long estimatedMicrocredits(String toolName, AgentContext context) {
        if (context == null || context.getRuntimeDependencies() == null
                || context.getRuntimeDependencies().getReactorConfig() == null) {
            return 0L;
        }
        if ("deep_search".equals(toolName)) {
            return context.getRuntimeDependencies().getReactorConfig().getDeepSearchMicrocredits();
        }
        if ("image_generation_tool".equals(toolName)) {
            return context.getRuntimeDependencies().getReactorConfig().getImageGenerationMicrocredits();
        }
        return 0L;
    }

    private Object parseToolArguments(String rawArguments) throws Exception {
        String payload = StringUtils.isBlank(rawArguments) ? "{}" : rawArguments;
        return objectMapper.readValue(payload, Object.class);
    }

    private ToolInputSchemaValidator.ValidationResult validateToolInput(String toolName, Object args) {
        ToolCollection tools = toolCatalog();
        if (tools == null || StringUtils.isBlank(toolName)) {
            return ToolInputSchemaValidator.ValidationResult.validResult();
        }

        BaseTool localTool = tools.getTool(toolName);
        if (localTool != null) {
            try {
                return inputSchemaValidator.validate(localTool.toParams(), args);
            } catch (Exception schemaError) {
                log.warn("{} local tool schema load failed tool={} errorType={}",
                        requestId(), toolName, schemaError.getClass().getSimpleName());
                return ToolInputSchemaValidator.ValidationResult.invalid(
                        "Tool schema could not be loaded.");
            }
        }

        McpToolInfo mcpTool = tools.getMcpTool(toolName);
        if (mcpTool == null) {
            // Unknown or non-exposed tools are rejected by PermissionPolicy.
            return ToolInputSchemaValidator.ValidationResult.validResult();
        }
        if (StringUtils.isBlank(mcpTool.getParameters())) {
            return ToolInputSchemaValidator.ValidationResult.invalid("Tool schema is missing.");
        }
        try {
            Object schema = objectMapper.readValue(mcpTool.getParameters(), Object.class);
            return inputSchemaValidator.validate(schema, args);
        } catch (Exception schemaError) {
            log.warn("{} MCP tool schema parse failed tool={} errorType={}",
                    requestId(), toolName, schemaError.getClass().getSimpleName());
            return ToolInputSchemaValidator.ValidationResult.invalid(
                    "Tool schema is not valid JSON.");
        }
    }

    private ToolExecutionOutcome toolInputValidationFailure(String toolName, String reason) {
        String detail = StringUtils.defaultIfBlank(reason, "Input does not match the tool schema.");
        String message = "Tool " + toolName
                + " input rejected by server-side schema validation: " + detail;
        return ToolExecutionOutcome.inputFailure(
                message, message, "tool input schema validation failed: " + detail);
    }

    private Object executeSelectedTool(ToolCall command, String toolName, Object args) {
        ToolCollection tools = toolCatalog();
        if (tools == null) {
            return null;
        }
        AgentContext context = context();
        if (context == null) {
            return tools.execute(toolName, args);
        }
        ToolArtifactSource artifactSource = ToolArtifactSource.builder()
                .sessionId(context.getSessionId())
                .requestId(context.getRequestId())
                .toolCallId(command.getId())
                .toolName(toolName)
                .build();
        context.bindCurrentToolArtifactSource(artifactSource);
        try {
            return tools.execute(toolName, args);
        } finally {
            context.clearCurrentToolArtifactSource();
        }
    }

    private void completeToolCallOnce(Map<String, ToolExecutionOutcome> result,
                                      ToolCall command,
                                      Map<String, Integer> dispatchIndexMapping,
                                      ToolExecutionOutcome outcome) {
        if (command == null || StringUtils.isBlank(command.getId()) || outcome == null) {
            return;
        }
        ToolExecutionOutcome previous = result.putIfAbsent(command.getId(), outcome);
        if (previous != null) {
            return;
        }
        operationLedger.recordSuccessful(command, outcome);
        recordToolExecutionEvidence(command, outcome);
        finishToolInvocation(command, outcome);
        recordToolArtifacts(command);
        emitToolCallFinishedEvent(command, dispatchIndexMapping.get(command.getId()), outcome);
    }

    private boolean reserveToolCallBudget(int requested) {
        if (requested <= 0) {
            return true;
        }
        AgentRunBudget budget = host.runBudget() == null ? AgentRunBudget.defaults() : host.runBudget();
        if (reservedToolCallCount + requested > budget.maxToolCalls()) {
            host.stop(AgentStopReason.TOOL_CALL_BUDGET);
            return false;
        }
        reservedToolCallCount += requested;
        AgentContext context = context();
        if (context != null && context.getAgentRunState() != null) {
            context.getAgentRunState().recordToolCalls(requested);
        }
        return true;
    }

    private ToolExecutionOutcome toolBudgetFailure() {
        String message = "Tool call budget exceeded; stop this run without invoking more tools.";
        return ToolExecutionOutcome.failure(message, message, null, message);
    }

    private ToolExecutionOutcome toolTimeBudgetFailure() {
        String message = "Tool execution stopped because the agent run time budget was exhausted.";
        return ToolExecutionOutcome.failure(message, message, null, message);
    }

    private ToolExecutionOutcome toolDownstreamAbortedFailure() {
        String message = "Tool execution cancelled because the downstream client disconnected.";
        return ToolExecutionOutcome.failure(message, message, null, message);
    }

    private ToolExecutionOutcome toolExecutionFailure() {
        String message = "Tool execution failed before producing a result.";
        return ToolExecutionOutcome.failure(message, message, null, message);
    }

    private Map<String, Integer> buildDispatchIndexMapping(List<ToolCall> commands) {
        Map<String, Integer> dispatchIndexMapping = new LinkedHashMap<>();
        if (commands == null || commands.isEmpty()) {
            return dispatchIndexMapping;
        }
        int dispatchIndex = 1;
        for (ToolCall command : commands) {
            if (command == null || StringUtils.isBlank(command.getId())) {
                continue;
            }
            dispatchIndexMapping.put(command.getId(), dispatchIndex++);
        }
        return dispatchIndexMapping;
    }

    private void emitToolCallRunningEvents(List<ToolCall> commands,
                                           Map<String, Integer> dispatchIndexMapping) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (ToolCall command : commands) {
            emitToolCallEvent(command,
                    dispatchIndexMapping.get(command == null ? null : command.getId()),
                    "running", false, null);
        }
    }

    private void emitToolCallFinishedEvent(ToolCall command,
                                           Integer dispatchIndex,
                                           ToolExecutionOutcome outcome) {
        String status = outcome != null && outcome.isSuccess() ? "success" : "failed";
        emitToolCallEvent(command, dispatchIndex, status, true, outcome);
    }

    private void emitToolCallEvent(ToolCall command,
                                   Integer dispatchIndex,
                                   String status,
                                   boolean isFinal,
                                   ToolExecutionOutcome outcome) {
        Printer printer = host.printer();
        if (printer == null || command == null || command.getFunction() == null) {
            return;
        }
        String toolCallId = command.getId();
        String toolName = command.getFunction().getName();
        if (StringUtils.isBlank(toolCallId) || StringUtils.isBlank(toolName)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageType", "tool_call");
        payload.put("status", status);
        payload.put("toolName", toolName);
        payload.put("toolCallId", toolCallId);
        payload.put("toolProvider", resolveToolProvider(toolName));
        if (dispatchIndex != null) {
            payload.put("dispatchIndex", dispatchIndex);
        }

        AgentContext context = context();
        Long toolInvocationId = context == null || context.getAgentRunState() == null
                ? null
                : context.getAgentRunState().resolveToolInvocationId(toolCallId);
        if (toolInvocationId != null) {
            payload.put("toolInvocationId", String.valueOf(toolInvocationId));
        }

        Object input = parseToolCallInput(command.getFunction().getArguments());
        if (input != null) {
            payload.put("input", input);
        }
        payload.put("summary", buildToolCallSummary(toolName, status));
        payload.put("isFinal", isFinal);
        if (outcome != null && StringUtils.isNotBlank(outcome.getErrorMsg())) {
            payload.put("errorMsg", outcome.getErrorMsg());
        }
        if (outcome != null && outcome.isReused()) {
            payload.put("reused", true);
        }
        if (!isFinal) {
            printer.send(new AgentStreamEvent.ToolStart(
                    requestId(), toolCallId, toolName, input));
            return;
        }
        printer.send(new AgentStreamEvent.ToolEnd(
                requestId(), toolCallId, toolName, payload,
                outcome != null && outcome.isSuccess(), 0L));
    }

    private Object parseToolCallInput(String arguments) {
        try {
            return objectMapper.readValue(normalizeToolPayload(arguments), Object.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String buildToolCallSummary(String toolName, String status) {
        if ("success".equals(status)) {
            return toolName + " 调用完成";
        }
        if ("failed".equals(status)) {
            return toolName + " 调用失败";
        }
        return "正在调用 " + toolName;
    }

    private Map<String, Long> ensureToolInvocationIds(List<ToolCall> commands) {
        AgentContext context = context();
        if (context == null || context.getAgentRunState() == null || commands == null || commands.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> existing = new LinkedHashMap<>();
        List<ToolCall> missingCommands = new ArrayList<>();
        for (ToolCall command : commands) {
            if (command == null || StringUtils.isBlank(command.getId())) {
                continue;
            }
            Long existingInvocationId = context.getAgentRunState().resolveToolInvocationId(command.getId());
            if (existingInvocationId != null) {
                existing.put(command.getId(), existingInvocationId);
            } else {
                missingCommands.add(command);
            }
        }
        if (missingCommands.isEmpty()) {
            return existing;
        }
        Map<String, Long> created = preRegisterToolInvocations(missingCommands);
        if (existing.isEmpty()) {
            return created;
        }
        if (!created.isEmpty()) {
            existing.putAll(created);
        }
        return existing;
    }

    private Map<String, Long> preRegisterToolInvocations(List<ToolCall> commands) {
        AgentContext context = context();
        if (context == null || !context.hasActiveLedgerRun() || context.getAgentRunState() == null) {
            return Map.of();
        }
        Long llmInvocationId = context.getAgentRunState().getCurrentLlmInvocationId();
        if (llmInvocationId == null) {
            return Map.of();
        }
        List<ToolInvocationBatchStartRecord.Item> items = new ArrayList<>(commands.size());
        int dispatchIndex = 1;
        for (ToolCall command : commands) {
            if (command == null || command.getFunction() == null
                    || StringUtils.isBlank(command.getFunction().getName())) {
                continue;
            }
            items.add(ToolInvocationBatchStartRecord.Item.builder()
                    .toolCallId(command.getId())
                    .dispatchIndex(dispatchIndex++)
                    .toolName(command.getFunction().getName())
                    .toolProvider(resolveToolProvider(command.getFunction().getName()))
                    .inputJson(normalizeToolPayload(command.getFunction().getArguments()))
                    .startedAt(LocalDateTime.now())
                    .build());
        }
        if (items.isEmpty()) {
            return Map.of();
        }
        return context.getExecutionRecorder().createToolInvocations(ToolInvocationBatchStartRecord.builder()
                .runId(context.getAgentRunState().getRunId())
                .requestId(context.getRequestId())
                .llmInvocationId(llmInvocationId)
                .agentName(host.agentName())
                .stepNo(host.currentStep())
                .items(items)
                .build());
    }

    private void finishToolInvocation(ToolCall command, ToolExecutionOutcome outcome) {
        AgentContext context = context();
        if (context == null || !context.hasActiveLedgerRun()
                || context.getAgentRunState() == null || command == null) {
            return;
        }
        Long toolInvocationId = context.getAgentRunState().resolveToolInvocationId(command.getId());
        if (toolInvocationId == null) {
            return;
        }
        context.getExecutionRecorder().finishToolInvocation(ToolInvocationFinishRecord.builder()
                .toolInvocationId(toolInvocationId)
                .runId(context.getAgentRunState().getRunId())
                .requestId(context.getRequestId())
                .sessionId(context.getSessionId())
                .toolCallId(command.getId())
                .toolName(command.getFunction().getName())
                .status(outcome != null && outcome.isSuccess()
                        ? ExecutionLedgerConstants.STATUS_SUCCESS
                        : ExecutionLedgerConstants.STATUS_FAILED)
                .toolResult(outcome == null ? null : outcome.getToolResult())
                .llmObservation(outcome == null ? null : outcome.getLlmObservation())
                .structuredOutput(outcome == null ? null : outcome.getStructuredOutput())
                .errorMsg(outcome == null ? null : outcome.getErrorMsg())
                .finishedAt(LocalDateTime.now())
                .build());
    }

    private void recordToolArtifacts(ToolCall command) {
        AgentContext context = context();
        if (context == null || !context.hasActiveLedgerRun()
                || context.getAgentRunState() == null || command == null) {
            return;
        }
        Long toolInvocationId = context.getAgentRunState().resolveToolInvocationId(command.getId());
        if (toolInvocationId == null) {
            return;
        }
        List<ArtifactRecordCommand> artifactCommands = new ArrayList<>();
        for (var binding : context.getArtifactBindingsByToolCallId(command.getId())) {
            if (binding == null || binding.getSource() == null || binding.getFile() == null) {
                continue;
            }
            File file = binding.getFile();
            artifactCommands.add(ArtifactRecordCommand.builder()
                    .runId(context.getAgentRunState().getRunId())
                    .requestId(context.getRequestId())
                    .toolInvocationId(toolInvocationId)
                    .toolCallId(command.getId())
                    .artifactRole(ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT)
                    .visibility(binding.isInternalFile()
                            ? ExecutionLedgerConstants.VISIBILITY_INTERNAL
                            : ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                    .sourceType(ExecutionLedgerConstants.SOURCE_TYPE_TOOL_OUTPUT)
                    .sourceName(binding.getSource().getToolName())
                    .fileName(file.getFileName())
                    .storageKey(resolveStorageKey(file))
                    .downloadUrl(file.getOssUrl())
                    .previewUrl(file.getDomainUrl())
                    .fileSize(file.getFileSize() == null ? null : file.getFileSize().longValue())
                    .metadataJson(buildArtifactMetadata(file))
                    .build());
        }
        if (!artifactCommands.isEmpty()) {
            context.getExecutionRecorder().recordArtifacts(artifactCommands);
        }
    }

    private ToolExecutionOutcome finalizeToolExecutionOutcome(ToolCall command,
                                                              ToolExecutionOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        String observation = StringUtils.defaultString(outcome.getLlmObservation());
        Integer maxObserve = host.maxObserveLength();
        if (maxObserve != null && maxObserve > 0 && observation.length() > maxObserve) {
            observation = observation.substring(0, maxObserve);
        }
        AgentContext context = context();
        if (context != null && command != null && StringUtils.isNotBlank(command.getId())) {
            observation = ToolArtifactFormatter.appendToolArtifactSummary(
                    observation,
                    context.getArtifactBindingsByToolCallId(command.getId())
            );
        }
        ToolExecutionOutcome finalized = outcome.setLlmObservation(observation);
        HookBus hookBus = host.hookBus();
        if (hookBus != null && command != null && command.getFunction() != null) {
            hookBus.fire(new HookBus.HookEvent(
                    finalized.isSuccess() ? HookBus.HookPoint.POST_TOOL : HookBus.HookPoint.TOOL_FAILURE,
                    context(),
                    command.getFunction().getName(),
                    parseToolCallInput(command.getFunction().getArguments()),
                    finalized));
        }
        return finalized;
    }

    private void recordToolExecutionEvidence(ToolCall command, ToolExecutionOutcome outcome) {
        AgentContext context = context();
        if (context == null || command == null || command.getFunction() == null || outcome == null) {
            return;
        }
        TodoStepEvidenceScope todoScope = todoEvidenceScopes.get(command.getId());
        context.recordToolExecutionEvidence(ToolExecutionEvidence.builder()
                .toolCallId(command.getId())
                .toolName(command.getFunction().getName())
                .operationKey(operationLedger.operationKey(command))
                .success(outcome.isSuccess())
                .errorMessage(outcome.getErrorMsg())
                .correctableInputFailure(outcome.isCorrectableInputFailure())
                .todoStepIndex(todoScope == null ? null : todoScope.stepIndex())
                .todoStepActivationId(todoScope == null ? null : todoScope.activationId())
                .reused(outcome.isReused())
                .build());
    }

    /**
     * Capture scope before any command in the provider batch executes. A Todo
     * mutation and a business call emitted in the same batch therefore cannot
     * retroactively attach that call to the newly activated item.
     */
    private void captureTodoEvidenceScopes(List<ToolCall> commands) {
        ToolCollection catalog = toolCatalog();
        if (catalog == null || commands == null || commands.isEmpty()) {
            return;
        }
        BaseTool todoTool = catalog.getTool(TodoWriteTool.NAME);
        if (!(todoTool instanceof TodoWriteTool todoWriteTool)) {
            return;
        }
        TodoStepEvidenceScope scope = todoWriteTool.getCurrentStepEvidenceScope();
        if (scope == null) {
            return;
        }
        for (ToolCall command : commands) {
            if (command == null || StringUtils.isBlank(command.getId())
                    || command.getFunction() == null
                    || TodoWriteTool.NAME.equals(command.getFunction().getName())) {
                continue;
            }
            todoEvidenceScopes.put(command.getId(), scope);
        }
    }

    private void validateToolCallIds(List<ToolCall> commands) {
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < commands.size(); index++) {
            ToolCall command = commands.get(index);
            String toolCallId = command == null ? null : StringUtils.trimToNull(command.getId());
            if (toolCallId == null) {
                throw new ToolDispatchRejectedException(
                        ToolDispatchRejectionReason.MISSING_TOOL_CALL_ID, index, null);
            }
            if (!ids.add(toolCallId)) {
                throw new ToolDispatchRejectedException(
                        ToolDispatchRejectionReason.DUPLICATE_TOOL_CALL_ID, index, toolCallId);
            }
        }
    }

    private boolean allowRepeatedSuccessfulCall(String toolName) {
        ToolCollection tools = toolCatalog();
        if (tools == null || StringUtils.isBlank(toolName)) {
            return false;
        }
        BaseTool localTool = tools.getTool(toolName);
        if (localTool != null) {
            return localTool.allowRepeatedSuccessfulCall();
        }
        McpToolInfo mcpTool = tools.getMcpTool(toolName);
        return mcpTool != null && mcpTool.isAllowRepeatedSuccessfulCall();
    }

    public enum ToolDispatchRejectionReason {
        MISSING_TOOL_CALL_ID,
        DUPLICATE_TOOL_CALL_ID
    }

    /** Typed, fail-closed rejection raised before any tool in a malformed batch executes. */
    public static final class ToolDispatchRejectedException extends IllegalArgumentException {

        private final ToolDispatchRejectionReason reason;
        private final int batchIndex;
        private final String toolCallId;

        public ToolDispatchRejectedException(ToolDispatchRejectionReason reason,
                                             int batchIndex,
                                             String toolCallId) {
            super(buildMessage(reason, batchIndex, toolCallId));
            this.reason = reason;
            this.batchIndex = batchIndex;
            this.toolCallId = toolCallId;
        }

        public ToolDispatchRejectionReason getReason() {
            return reason;
        }

        public int getBatchIndex() {
            return batchIndex;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        private static String buildMessage(ToolDispatchRejectionReason reason,
                                           int batchIndex,
                                           String toolCallId) {
            if (reason == ToolDispatchRejectionReason.DUPLICATE_TOOL_CALL_ID) {
                return "Tool call batch rejected: duplicate toolCallId at index "
                        + batchIndex + " (" + toolCallId + ").";
            }
            return "Tool call batch rejected: missing toolCallId at index " + batchIndex + ".";
        }
    }

    private ToolResultPayload normalizeToolResultPayload(Object rawResult) {
        if (rawResult instanceof ToolResultPayload payload) {
            String toolResult = StringUtils.defaultString(payload.getToolResult());
            return ToolResultPayload.builder()
                    .toolResult(toolResult)
                    .llmObservation(StringUtils.defaultIfBlank(payload.getLlmObservation(), toolResult))
                    .structuredOutput(payload.getStructuredOutput())
                    .failed(Boolean.TRUE.equals(payload.getFailed()))
                    .errorMsg(payload.getErrorMsg())
                    .build();
        }
        if (rawResult instanceof String textResult) {
            return ToolResultPayload.builder()
                    .toolResult(textResult)
                    .llmObservation(textResult)
                    .failed(Boolean.FALSE)
                    .build();
        }
        try {
            String serialized = objectMapper.writeValueAsString(rawResult);
            return ToolResultPayload.builder()
                    .toolResult(serialized)
                    .llmObservation(serialized)
                    .failed(Boolean.FALSE)
                    .build();
        } catch (Exception error) {
            String fallback = String.valueOf(rawResult);
            return ToolResultPayload.builder()
                    .toolResult(fallback)
                    .llmObservation(fallback)
                    .failed(Boolean.FALSE)
                    .build();
        }
    }

    private String normalizeToolPayload(String payload) {
        if (StringUtils.isBlank(payload)) {
            return "{}";
        }
        try {
            return objectMapper.readTree(payload).toString();
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private String resolveToolProvider(String toolName) {
        ToolCollection tools = toolCatalog();
        if (tools == null || StringUtils.isBlank(toolName)) {
            return ExecutionLedgerConstants.TOOL_PROVIDER_LOCAL;
        }
        if (tools.getMcpToolMap() != null && tools.getMcpToolMap().containsKey(toolName)) {
            return ExecutionLedgerConstants.TOOL_PROVIDER_MCP;
        }
        return ExecutionLedgerConstants.TOOL_PROVIDER_LOCAL;
    }

    private Executor resolveToolExecutor() {
        AgentContext context = context();
        if (context == null || context.getRuntimeDependencies() == null) {
            return Runnable::run;
        }
        return context.getRuntimeDependencies().requireToolExecutor();
    }

    private ToolCollection executionTools() {
        return host.executionTools();
    }

    private ToolCollection toolCatalog() {
        ToolCollection catalog = host.toolCatalog();
        return catalog == null ? executionTools() : catalog;
    }

    private ToolCollection permissionTools(ToolCall command, String toolName) {
        ToolCollection active = executionTools();
        if (command == null || !proxiedToolCallIds.contains(command.getId())) {
            return active;
        }
        ToolCollection catalog = toolCatalog();
        if (catalog == null) {
            return active;
        }
        Set<String> authorizedNames = new HashSet<>();
        if (active != null) {
            authorizedNames.addAll(active.getToolMap().keySet());
            authorizedNames.addAll(active.getMcpToolMap().keySet());
        }
        authorizedNames.add(toolName);
        return catalog.selectedView(authorizedNames);
    }

    private boolean containsTool(ToolCollection tools, String toolName) {
        return tools != null && StringUtils.isNotBlank(toolName)
                && (tools.getToolMap().containsKey(toolName)
                || tools.getMcpToolMap().containsKey(toolName));
    }

    private AgentContext context() {
        return host.context();
    }

    private String requestId() {
        AgentContext context = context();
        return context == null ? null : context.getRequestId();
    }

    private String resolveStorageKey(File file) {
        if (file == null) {
            return "";
        }
        if (StringUtils.isNotBlank(file.getOriginOssUrl())) {
            return file.getOriginOssUrl();
        }
        if (StringUtils.isNotBlank(file.getOssUrl())) {
            return file.getOssUrl();
        }
        if (StringUtils.isNotBlank(file.getOriginDomainUrl())) {
            return file.getOriginDomainUrl();
        }
        if (StringUtils.isNotBlank(file.getDomainUrl())) {
            return file.getDomainUrl();
        }
        return StringUtils.defaultString(file.getFileName());
    }

    private String buildArtifactMetadata(File file) {
        if (file == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(file.getDescription())) {
            metadata.put("description", file.getDescription());
        }
        if (StringUtils.isNotBlank(file.getOriginFileName())) {
            metadata.put("originFileName", file.getOriginFileName());
        }
        if (StringUtils.isNotBlank(file.getOriginDomainUrl())) {
            metadata.put("originDomainUrl", file.getOriginDomainUrl());
        }
        if (metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ignore) {
            return null;
        }
    }

    public interface Host {
        AgentContext context();

        Printer printer();

        ToolCollection executionTools();

        default ToolCollection toolCatalog() {
            return executionTools();
        }

        AgentRunBudget runBudget();

        RetryPolicy retryPolicy();

        PermissionPolicy permissionPolicy();

        HookBus hookBus();

        String singleUseToolName();

        String agentName();

        int currentStep();

        Integer maxObserveLength();

        Duration remainingRunDuration();

        boolean isDownstreamAborted();

        void stop(AgentStopReason reason);
    }
}
