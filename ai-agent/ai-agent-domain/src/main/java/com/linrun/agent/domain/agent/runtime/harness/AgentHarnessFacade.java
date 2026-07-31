package com.linrun.agent.domain.agent.runtime.harness;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolExecutionOutcome;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Single runtime boundary for Standard and DEEP model/tool work. Graph code may
 * orchestrate this port but must not construct an AgentLoop or call tools/models itself.
 */
public interface AgentHarnessFacade {

    AgentRunContext bind(AgentContext context);

    ContextProjection projectContext(AgentContext context);

    StructuredStepResult runStructuredStep(AgentContext context, StructuredStepRequest request);

    ToolLoopResult runToolLoop(AgentContext context, ToolLoopRequest request);

    ToolExecutionOutcome executeTool(AgentContext context, ToolCall toolCall);

    Long recordModelInvocation(AgentContext context, ModelInvocationRecord record);

    Long recordToolAttempt(AgentContext context, ToolAttemptRecord record);

    QuotaBillingPort.Reservation reserveQuota(AgentContext context,
                                              String abilityCode,
                                              long requestedMicrocredits,
                                              long minimumMicrocredits);

    QuotaBillingPort.SettlementResult settleQuota(AgentContext context,
                                                   String freezeId,
                                                   long actualMicrocredits);

    QuotaBillingPort.SettlementResult releaseQuota(AgentContext context, String freezeId);

    record ContextProjection(AgentRunContext run,
                             String systemPrompt,
                             List<String> exposedToolNames,
                             Duration remainingDuration) {
        public ContextProjection {
            exposedToolNames = exposedToolNames == null ? List.of() : List.copyOf(exposedToolNames);
        }
    }

    /**
     * A structured step must declare the JSON shape it accepts. This prevents a
     * caller from treating text that merely resembles JSON as a valid graph value.
     */
    record StructuredStepRequest(String prompt, AgentRunBudget budget, StructuredOutputSchema outputSchema) {
        public StructuredStepRequest(String prompt, AgentRunBudget budget) {
            this(prompt, budget, StructuredOutputSchema.object());
        }

        public StructuredStepRequest {
            outputSchema = Objects.requireNonNull(outputSchema, "structured step output schema must not be null");
        }
    }

    record StructuredStepResult(String output, AgentStopReason stopReason, boolean completed) {
    }

    /** Minimal JSON Schema-equivalent contract used at the runtime boundary. */
    record StructuredOutputSchema(JsonValueType rootType, List<String> requiredProperties) {
        public StructuredOutputSchema {
            rootType = Objects.requireNonNull(rootType, "structured output root type must not be null");
            requiredProperties = requiredProperties == null ? List.of() : List.copyOf(requiredProperties);
            if (rootType != JsonValueType.OBJECT && !requiredProperties.isEmpty()) {
                throw new IllegalArgumentException("only object JSON output may declare required properties");
            }
            if (requiredProperties.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("structured output required property must not be blank");
            }
        }

        public static StructuredOutputSchema object(String... requiredProperties) {
            return new StructuredOutputSchema(JsonValueType.OBJECT,
                    requiredProperties == null ? List.of() : List.of(requiredProperties));
        }

        public static StructuredOutputSchema array() {
            return new StructuredOutputSchema(JsonValueType.ARRAY, List.of());
        }
    }

    enum JsonValueType {
        OBJECT,
        ARRAY,
        STRING,
        NUMBER,
        BOOLEAN,
        NULL
    }

    record ToolLoopRequest(String prompt,
                           AgentRunBudget budget,
                           boolean propagateFailureToContext,
                           List<ToolCall> preflightToolCalls) {
        public ToolLoopRequest {
            preflightToolCalls = preflightToolCalls == null ? List.of() : List.copyOf(preflightToolCalls);
        }

        public static ToolLoopRequest standard(String prompt) {
            return new ToolLoopRequest(prompt, null, true, List.of());
        }
    }

    record ToolLoopResult(String answer, AgentLoop agentLoop) {
    }

    record ModelInvocationRecord(String callKind,
                                 String modelName,
                                 String promptHash,
                                 boolean streaming,
                                 int status,
                                 String responseText,
                                 String errorMessage) {
    }

    record ToolAttemptRecord(String toolCallId,
                             String toolName,
                             String inputJson,
                             int status,
                             String result,
                             String errorMessage) {
    }
}
