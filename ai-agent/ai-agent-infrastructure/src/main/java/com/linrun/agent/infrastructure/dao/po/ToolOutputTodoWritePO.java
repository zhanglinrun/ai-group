package com.linrun.agent.infrastructure.dao.po;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** todo_write structured output row. */
@Data
@EqualsAndHashCode(callSuper = true)
public class ToolOutputTodoWritePO extends AbstractToolOutputPO {
    private String command;
    private String beforeTodoJson;
    private String afterTodoJson;
    private String currentStep;
    private Integer currentStepIndex;
    private Boolean autoAdvanced;
    private Boolean autoFinished;
}
