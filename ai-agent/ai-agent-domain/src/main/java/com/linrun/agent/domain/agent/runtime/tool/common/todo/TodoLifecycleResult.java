package com.linrun.agent.domain.agent.runtime.tool.common.todo;

import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Result of a todo-list lifecycle mutation. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoLifecycleResult {

    /** Latest todo list after the lifecycle action. */
    private TodoList todoList;

    /** Current executable item. */
    private String currentStep;

    /** Index of the current executable item. */
    private Integer currentStepIndex;

    /** Whether the service automatically advanced to another item. */
    @Builder.Default
    private Boolean autoAdvanced = Boolean.FALSE;

    /** Whether the todo list became complete. */
    @Builder.Default
    private Boolean autoFinished = Boolean.FALSE;
}
