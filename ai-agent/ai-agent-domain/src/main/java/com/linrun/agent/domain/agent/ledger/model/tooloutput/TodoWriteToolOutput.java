package com.linrun.agent.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;

/** Structured before/after snapshot persisted for todo_write. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoWriteToolOutput implements ToolStructuredOutput {

    private String command;
    private TodoList beforeTodo;
    private TodoList afterTodo;
    private String currentStep;
    private Integer currentStepIndex;
    @Builder.Default
    private Boolean autoAdvanced = Boolean.FALSE;
    @Builder.Default
    private Boolean autoFinished = Boolean.FALSE;

    @Override
    public String getToolName() {
        return ToolOutputNames.TODO_WRITE;
    }
}
