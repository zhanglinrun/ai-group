package com.linrun.agent.domain.agent.runtime.agent;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import com.linrun.agent.domain.agent.runtime.dto.Memory;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactFormatter;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.enums.RoleType;
import com.linrun.agent.domain.agent.runtime.harness.AgentFutureWaiter;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.harness.DefaultPermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.HookBus;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.RetryPolicy;
import com.linrun.agent.domain.agent.runtime.harness.StopGate;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolDispatcher;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolExecutionOutcome;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Stable Agent Loop shell. Model policy belongs to the concrete agent while
 * mechanical stopping and tool execution are delegated to Harness components.
 */
@Slf4j
@Data
@Accessors(chain = true)
public abstract class BaseAgent {

    private String name;
    public ToolCollection availableTools = new ToolCollection();
    private transient ToolCollection activeToolsForTurn;
    private Memory memory = new Memory();
    private String functionCallType;
    protected AgentContext context;
    private AgentState state = AgentState.IDLE;
    private AgentRunBudget runBudget = AgentRunBudget.defaults();
    private AgentStopReason stopReason = AgentStopReason.NONE;
    private int currentStep;
    private StopGate stopGate = new StopGate();
    private RetryPolicy retryPolicy = RetryPolicy.noRetry();
    private PermissionPolicy permissionPolicy;
    private HookBus hookBus;
    private String singleUseToolName;
    private boolean propagateFailureToContext = true;
    protected Printer printer;

    private final transient ToolDispatcher toolDispatcher = new ToolDispatcher(new ToolDispatcher.Host() {
        @Override
        public AgentContext context() {
            return BaseAgent.this.context;
        }

        @Override
        public Printer printer() {
            return BaseAgent.this.printer;
        }

        @Override
        public ToolCollection executionTools() {
            return BaseAgent.this.executionTools();
        }

        @Override
        public ToolCollection toolCatalog() {
            return BaseAgent.this.availableTools;
        }

        @Override
        public AgentRunBudget runBudget() {
            return BaseAgent.this.runBudget;
        }

        @Override
        public RetryPolicy retryPolicy() {
            return BaseAgent.this.retryPolicy;
        }

        @Override
        public PermissionPolicy permissionPolicy() {
            return BaseAgent.this.permissionPolicy;
        }

        @Override
        public HookBus hookBus() {
            return BaseAgent.this.hookBus;
        }

        @Override
        public String singleUseToolName() {
            return BaseAgent.this.singleUseToolName;
        }

        @Override
        public String agentName() {
            return BaseAgent.this.name;
        }

        @Override
        public int currentStep() {
            return BaseAgent.this.currentStep;
        }

        @Override
        public Integer maxObserveLength() {
            return BaseAgent.this.resolveMaxObserveLength();
        }

        @Override
        public Duration remainingRunDuration() {
            return BaseAgent.this.remainingRunDuration();
        }

        @Override
        public boolean isDownstreamAborted() {
            return BaseAgent.this.isDownstreamAborted();
        }

        @Override
        public void stop(AgentStopReason reason) {
            BaseAgent.this.terminateRun(reason);
        }
    });

    public static final String TERMINATION_MAX_TURNS = "Terminated: Reached max turns";
    public static final String TERMINATION_STUCK =
            "Terminated: Detected repeated steps, stopping to avoid a dead loop";

    protected BaseAgent() {
        this(new DefaultPermissionPolicy(), new HookBus());
    }

    protected BaseAgent(PermissionPolicy permissionPolicy, HookBus hookBus) {
        this.permissionPolicy = permissionPolicy == null
                ? new DefaultPermissionPolicy()
                : permissionPolicy;
        this.hookBus = hookBus == null ? new HookBus() : hookBus;
    }

    public abstract String step();

    public String run(String query) {
        state = AgentState.IDLE;
        stopReason = AgentStopReason.NONE;
        currentStep = 0;
        stopGate.beginRun(context, runBudget);
        toolDispatcher.reset();
        activeToolsForTurn = null;

        if (query != null && !query.isEmpty()) {
            updateMemory(RoleType.USER, query, null);
        }

        List<String> results = new ArrayList<>();
        try {
            while (currentStep < runBudget.maxTurns() && state != AgentState.FINISHED) {
                if (isDownstreamAborted()) {
                    log.info("{} {} downstream aborted, stop agent loop at step {}",
                            context == null ? null : context.getRequestId(), name, currentStep);
                    failRun(AgentStopReason.DOWNSTREAM_ABORTED);
                    break;
                }
                AgentStopReason stop = stopGate.beforeTurn(context, runBudget);
                if (stop != AgentStopReason.NONE) {
                    results.add(terminateRun(stop));
                    break;
                }

                currentStep++;
                if (context != null) {
                    context.markExecutionPosition(name, currentStep);
                }
                log.info("{} {} executing turn {}/{}",
                        context == null ? null : context.getRequestId(), name, currentStep, runBudget.maxTurns());
                String stepResult = step();
                results.add(stepResult);

                String turnSignature = repetitionSignature(stepResult);
                if (state != AgentState.FINISHED && stopGate.isRepeatedTurn(turnSignature)) {
                    log.warn("{} {} detected repeated steps, breaking to avoid dead loop",
                            context == null ? null : context.getRequestId(), name);
                    results.add(TERMINATION_STUCK);
                    failRun(AgentStopReason.REPEATED_TURN);
                    currentStep = 0;
                    break;
                }
            }

            if (state != AgentState.FINISHED && currentStep >= runBudget.maxTurns()) {
                failRun(AgentStopReason.MAX_TURNS);
                currentStep = 0;
                results.add(TERMINATION_MAX_TURNS + " (" + runBudget.maxTurns() + ")");
            }
        } catch (Exception error) {
            stopReason = AgentStopReason.EXECUTION_ERROR;
            state = AgentState.ERROR;
            if (propagateFailureToContext && context != null) {
                context.cancel(AgentStopReason.EXECUTION_ERROR);
                context.markRunFailed();
            }
            throw error;
        }

        if (state == AgentState.FINISHED
                && stopReason == AgentStopReason.NONE
                && (context == null || !context.isRunFailed())) {
            stopReason = AgentStopReason.COMPLETED;
        }
        return results.isEmpty() ? "No steps executed" : results.get(results.size() - 1);
    }

    /**
     * Semantic identity for one turn. Generic agents fall back to answer content;
     * AgentLoop overrides this with the model-selected tool name and arguments.
     */
    protected String repetitionSignature(String stepResult) {
        return StopGate.contentSignature(stepResult);
    }

    private void failRun(AgentStopReason reason) {
        stopReason = reason;
        state = AgentState.FINISHED;
        if (propagateFailureToContext && context != null) {
            context.cancel(reason);
            context.markRunFailed();
        }
    }

    private String terminateRun(AgentStopReason reason) {
        failRun(reason);
        return "Terminated: " + reason.name();
    }

    private boolean isDownstreamAborted() {
        return (printer != null && printer.isAborted())
                || (context != null && context.cancellationReason() != AgentStopReason.NONE);
    }

    protected Duration remainingRunDuration() {
        return stopGate.remainingDuration(context, runBudget);
    }

    protected <T> T awaitWithinRun(java.util.concurrent.Future<T> future, Duration callLimit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return AgentFutureWaiter.await(future, context, callLimit);
    }

    public void setMaxSteps(int maxTurns) {
        runBudget = runBudget.withMaxTurns(maxTurns);
    }

    public int getMaxSteps() {
        return runBudget.maxTurns();
    }

    public void setDuplicateThreshold(int duplicateThreshold) {
        stopGate.setDuplicateThreshold(duplicateThreshold);
    }

    public int getDuplicateThreshold() {
        return stopGate.getDuplicateThreshold();
    }

    public void setToolMaxAttempts(int maxAttempts) {
        retryPolicy = new RetryPolicy(maxAttempts);
    }

    /** A bounded child branch can terminate locally without failing its parent run. */
    public void setPropagateFailureToContext(boolean propagateFailureToContext) {
        this.propagateFailureToContext = propagateFailureToContext;
    }

    public int getToolMaxAttempts() {
        return retryPolicy.maxAttempts();
    }

    public void updateMemory(RoleType role, String content, String base64Image, Object... args) {
        Message message = switch (role) {
            case USER -> Message.userMessage(content, base64Image);
            case SYSTEM -> Message.systemMessage(content, base64Image);
            case ASSISTANT -> Message.assistantMessage(content, base64Image);
            case TOOL -> Message.toolMessage(content, (String) args[0], base64Image);
        };
        memory.addMessage(message);
    }

    protected Integer resolveMaxObserveLength() {
        return null;
    }

    /** Compatibility helper; canonical observations are now finalized by ToolDispatcher. */
    protected String attachToolArtifactSummary(String result, String toolCallId) {
        if (context == null || toolCallId == null || toolCallId.isBlank()) {
            return result;
        }
        return ToolArtifactFormatter.appendToolArtifactSummary(
                result,
                context.getArtifactBindingsByToolCallId(toolCallId)
        );
    }

    protected String writeToolObservationToMemory(ToolCall command, ToolExecutionOutcome outcome) {
        String observation = outcome == null || outcome.getLlmObservation() == null
                ? ""
                : outcome.getLlmObservation();
        if (command == null) {
            return observation;
        }
        if ("struct_parse".equals(functionCallType)) {
            String content = memory.getLastMessage().getContent();
            memory.getLastMessage().setContent(content + "\n 工具执行结果为:\n" + observation);
            return observation;
        }
        memory.addMessage(Message.toolMessage(observation, command.getId(), null));
        return observation;
    }

    public String executeTool(ToolCall command) {
        return executeToolOutcome(command).getLlmObservation();
    }

    /**
     * Executes one tool through the canonical dispatcher and retains its typed
     * outcome for callers that must make protocol decisions. The string-returning
     * method above remains for legacy loop callers.
     */
    public ToolExecutionOutcome executeToolOutcome(ToolCall command) {
        return toolDispatcher.dispatch(command);
    }

    public Map<String, String> executeTools(List<ToolCall> commands) {
        Map<String, ToolExecutionOutcome> outcomes = executeToolOutcomes(commands);
        Map<String, String> result = new LinkedHashMap<>(outcomes.size());
        outcomes.forEach((key, value) -> result.put(
                key, value == null || value.getLlmObservation() == null ? "" : value.getLlmObservation()));
        return result;
    }

    protected Map<String, ToolExecutionOutcome> executeToolOutcomes(List<ToolCall> commands) {
        return toolDispatcher.dispatch(commands);
    }

    protected void activateToolsForTurn(ToolCollection tools) {
        activeToolsForTurn = tools;
    }

    protected ToolCollection executionTools() {
        return activeToolsForTurn == null ? availableTools : activeToolsForTurn;
    }
}
