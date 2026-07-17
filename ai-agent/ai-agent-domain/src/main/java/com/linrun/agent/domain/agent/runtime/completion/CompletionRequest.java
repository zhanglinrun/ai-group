package com.linrun.agent.domain.agent.runtime.completion;

import lombok.Builder;
import lombok.Value;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;

import java.util.List;

/** Immutable input to the stop/completion gate. */
@Value
@Builder
public class CompletionRequest {
    String goal;
    String draftAnswer;
    AgentExecutionProfile executionProfile;
    TodoList todoList;
    @Builder.Default
    List<ToolExecutionEvidence> toolEvidence = List.of();
    String requiredToolName;
    @Builder.Default
    ToolInvocationContract toolInvocationContract = ToolInvocationContract.none();
    @Builder.Default
    List<String> requiredOutputFields = List.of();
    boolean runFailed;
    boolean networkLookupRequired;
    boolean reportArtifactRequired;
    boolean reportArtifactPresent;
}
