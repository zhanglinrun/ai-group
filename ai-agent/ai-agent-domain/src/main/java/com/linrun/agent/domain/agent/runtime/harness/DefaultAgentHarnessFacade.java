package com.linrun.agent.domain.agent.runtime.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationFinishRecord;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.loop.ContextPipeline;
import com.linrun.agent.domain.agent.runtime.context.ContextProjectionRequest;
import com.linrun.agent.domain.agent.runtime.context.ContextProjectionService;
import com.linrun.agent.domain.agent.runtime.context.ContextRole;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshot;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshotService;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolExecutionOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Default P20 implementation backed by the existing AgentLoop, ledger and quota ports. */
@Service
public class DefaultAgentHarnessFacade implements AgentHarnessFacade {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentLoopFactory agentLoopFactory;

    @Autowired(required = false)
    private ContextSnapshotService contextSnapshotService;

    @Autowired(required = false)
    private ContextProjectionService contextProjectionService;

    public DefaultAgentHarnessFacade(AgentLoopFactory agentLoopFactory) {
        this.agentLoopFactory = Objects.requireNonNull(agentLoopFactory, "AgentLoopFactory must not be null");
    }

    @Override
    public AgentRunContext bind(AgentContext context) {
        Objects.requireNonNull(context, "AgentContext must not be null");
        if (!context.hasRunDeadline()) {
            context.activateRunDeadline(resolveBudget(context).maxDurationMillis());
        }
        return AgentRunContext.from(context);
    }

    @Override
    public ContextProjection projectContext(AgentContext context) {
        AgentRunContext run = bind(context);
        ToolCollection tools = context.getToolCollection() == null ? new ToolCollection() : context.getToolCollection();
        String systemPrompt = "";
        if (context.getRuntimeDependencies() != null && context.getRuntimeDependencies().getReactorConfig() != null) {
            ReactorConfig config = context.getRuntimeDependencies().getReactorConfig();
            ContextPipeline.PromptState state = new ContextPipeline().initialize(context, config, tools);
            systemPrompt = state.systemPromptSnapshot();
        }
        systemPrompt = appendSnapshotProjection(context, systemPrompt);
        LinkedHashSet<String> names = new LinkedHashSet<>(tools.getToolMap().keySet());
        names.addAll(tools.getMcpToolMap().keySet());
        return new ContextProjection(run, systemPrompt, new ArrayList<>(names), context.remainingRunDuration());
    }

    private String appendSnapshotProjection(AgentContext context, String systemPrompt) {
        if (contextSnapshotService == null || contextProjectionService == null) {
            return systemPrompt;
        }
        ContextSnapshot snapshot = contextSnapshotService.captureInitial(context);
        com.linrun.agent.domain.agent.runtime.context.ContextProjection projection = contextProjectionService.project(new ContextProjectionRequest(
                snapshot,
                ContextRole.fromAgentName(currentAgentName(context)),
                context.getQuery(), context.getTask(), List.of(), List.of(), List.of(), List.of(), "", List.of(),
                2_048));
        if (projection.rendered().isBlank()) {
            return systemPrompt;
        }
        return systemPrompt == null || systemPrompt.isBlank()
                ? projection.rendered()
                : systemPrompt.stripTrailing() + "\n\n" + projection.rendered();
    }

    @Override
    public StructuredStepResult runStructuredStep(AgentContext context, StructuredStepRequest request) {
        bind(context);
        StructuredStepRequest effective = Objects.requireNonNull(request, "structured step request must not be null");
        AgentLoop agentLoop = agentLoopFactory.create(context);
        AgentRunBudget budget = effective.budget() == null
                ? resolveBudget(context).withMaxTurns(1)
                : effective.budget().withMaxTurns(1);
        agentLoop.setRunBudget(budget);
        String output = agentLoop.step();
        validateStructuredOutput(output, effective.outputSchema());
        boolean completed = agentLoop.getState() == AgentState.FINISHED
                || agentLoop.getState() == AgentState.ERROR;
        return new StructuredStepResult(output, agentLoop.getStopReason(), completed);
    }

    @Override
    public ToolLoopResult runToolLoop(AgentContext context, ToolLoopRequest request) {
        bind(context);
        ToolLoopRequest effective = request == null ? ToolLoopRequest.standard(context.getQuery()) : request;
        AgentLoop agentLoop = agentLoopFactory.create(context);
        if (effective.budget() != null) {
            agentLoop.setRunBudget(effective.budget());
        }
        agentLoop.setPropagateFailureToContext(effective.propagateFailureToContext());
        for (ToolCall toolCall : effective.preflightToolCalls()) {
            agentLoop.executeTool(toolCall);
        }
        String answer = agentLoop.run(effective.prompt());
        return new ToolLoopResult(answer, agentLoop);
    }

    @Override
    public ToolExecutionOutcome executeTool(AgentContext context, ToolCall toolCall) {
        bind(context);
        AgentLoop agentLoop = agentLoopFactory.create(context);
        return agentLoop.executeToolOutcome(toolCall);
    }

    @Override
    public Long recordModelInvocation(AgentContext context, ModelInvocationRecord record) {
        AgentRunContext run = bind(context);
        AgentExecutionRecorder recorder = requireRecorder(context);
        ModelInvocationRecord effective = record == null
                ? new ModelInvocationRecord("harness", null, null, false,
                ExecutionLedgerConstants.STATUS_FAILED, null, "missing model invocation record")
                : record;
        Long invocationId = recorder.createLlmInvocation(LlmInvocationStartRecord.builder()
                .runId(run.runId())
                .requestId(run.requestId())
                .invocationSeq(context.getAgentRunState().nextInvocationSeq())
                .agentName(currentAgentName(context))
                .stepNo(currentStepNo(context))
                .callKind(effective.callKind())
                .streaming(effective.streaming())
                .modelName(effective.modelName())
                .costOwner("PLATFORM")
                .promptHash(effective.promptHash())
                .startedAt(LocalDateTime.now())
                .build());
        if (invocationId == null) {
            throw new IllegalStateException("model invocation ledger insert failed");
        }
        recorder.finishLlmInvocation(LlmInvocationFinishRecord.builder()
                .llmInvocationId(invocationId)
                .requestId(run.requestId())
                .status(effective.status())
                .responseText(effective.responseText())
                .errorMsg(effective.errorMessage())
                .finishedAt(LocalDateTime.now())
                .build());
        return invocationId;
    }

    @Override
    public Long recordToolAttempt(AgentContext context, ToolAttemptRecord record) {
        AgentRunContext run = bind(context);
        AgentExecutionRecorder recorder = requireRecorder(context);
        ToolAttemptRecord effective = Objects.requireNonNull(record, "Tool attempt record must not be null");
        Map<String, Long> ids = recorder.createToolInvocations(ToolInvocationBatchStartRecord.builder()
                .runId(run.runId())
                .requestId(run.requestId())
                .llmInvocationId(context.getAgentRunState().getCurrentLlmInvocationId())
                .agentName(currentAgentName(context))
                .stepNo(currentStepNo(context))
                .items(List.of(ToolInvocationBatchStartRecord.Item.builder()
                        .toolCallId(effective.toolCallId())
                        .dispatchIndex(0)
                        .toolName(effective.toolName())
                        .toolProvider("harness")
                        .inputJson(effective.inputJson())
                        .startedAt(LocalDateTime.now())
                        .build()))
                .build());
        Long invocationId = ids.get(effective.toolCallId());
        if (invocationId == null) {
            throw new IllegalStateException("tool invocation ledger insert failed");
        }
        recorder.finishToolInvocation(ToolInvocationFinishRecord.builder()
                .toolInvocationId(invocationId)
                .runId(run.runId())
                .requestId(run.requestId())
                .sessionId(run.sessionId())
                .toolCallId(effective.toolCallId())
                .toolName(effective.toolName())
                .status(effective.status())
                .toolResult(effective.result())
                .errorMsg(effective.errorMessage())
                .finishedAt(LocalDateTime.now())
                .build());
        return invocationId;
    }

    @Override
    public QuotaBillingPort.Reservation reserveQuota(AgentContext context,
                                                      String abilityCode,
                                                      long requestedMicrocredits,
                                                      long minimumMicrocredits) {
        AgentRunContext run = bind(context);
        return requireQuotaPort(context).reserve(run.userId(), requestedMicrocredits, minimumMicrocredits,
                abilityCode, run.requestId() + ":harness:" + abilityCode);
    }

    @Override
    public QuotaBillingPort.SettlementResult settleQuota(AgentContext context,
                                                           String freezeId,
                                                           long actualMicrocredits) {
        return requireQuotaPort(context).settleWithStatus(freezeId, actualMicrocredits);
    }

    @Override
    public QuotaBillingPort.SettlementResult releaseQuota(AgentContext context, String freezeId) {
        return requireQuotaPort(context).releaseWithStatus(freezeId);
    }

    private AgentRunBudget resolveBudget(AgentContext context) {
        if (context != null && context.getRuntimeDependencies() != null
                && context.getRuntimeDependencies().getReactorConfig() != null) {
            ReactorConfig config = context.getRuntimeDependencies().getReactorConfig();
            return new AgentRunBudget(
                    positive(config.getAgentLoopMaxTurns(), 40),
                    positive(config.getAgentLoopMaxToolCalls(), 64),
                    positive(config.getAgentLoopMaxCompletionAttempts(), 3),
                    positive(config.getAgentLoopMaxDurationSeconds(), 900L) * 1_000L,
                    positive(config.getAgentLoopMaxTotalTokens(), 200_000L),
                    positive(config.getAgentLoopMaxMicrocredits(), 10_000_000L));
        }
        return AgentRunBudget.defaults();
    }

    private AgentExecutionRecorder requireRecorder(AgentContext context) {
        if (context == null || !context.hasActiveLedgerRun()) {
            throw new IllegalStateException("active run ledger is required for Harness recording");
        }
        return context.getExecutionRecorder();
    }

    private QuotaBillingPort requireQuotaPort(AgentContext context) {
        if (context == null || context.getRuntimeDependencies() == null
                || context.getRuntimeDependencies().getQuotaBillingPort() == null) {
            throw new IllegalStateException("quota billing port is required for Harness quota operations");
        }
        return context.getRuntimeDependencies().getQuotaBillingPort();
    }

    private String currentAgentName(AgentContext context) {
        String agentName = context.getAgentRunState().getCurrentAgentName();
        return agentName == null || agentName.isBlank() ? "agent_harness" : agentName;
    }

    private Integer currentStepNo(AgentContext context) {
        return context.getAgentRunState().getCurrentStepNo() == null
                ? 0
                : context.getAgentRunState().getCurrentStepNo();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long positive(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private void validateStructuredOutput(String output, StructuredOutputSchema schema) {
        try {
            JsonNode value = JSON.readTree(Objects.requireNonNull(output, "structured step output must not be null"));
            if (value == null || !matches(value, schema.rootType())) {
                throw new IllegalArgumentException("structured output schema root type mismatch: expected "
                        + schema.rootType());
            }
            for (String property : schema.requiredProperties()) {
                if (!value.hasNonNull(property)) {
                    throw new IllegalArgumentException("structured output schema missing required property: " + property);
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("structured output schema validation failed", exception);
        }
    }

    private boolean matches(JsonNode value, JsonValueType expectedType) {
        return switch (expectedType) {
            case OBJECT -> value.isObject();
            case ARRAY -> value.isArray();
            case STRING -> value.isTextual();
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case NULL -> value.isNull();
        };
    }
}
