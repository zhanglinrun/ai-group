package org.wwz.ai.domain.agent.runtime.evaluation;

import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;

import java.util.List;

/**
 * Evidence available after one executor round.
 */
public record PlanEvaluationRequest(
        String query,
        String task,
        String executorResult,
        List<Message> messages,
        AgentState executorState,
        int stepNo,
        String currentDate
) {

    public PlanEvaluationRequest(String query,
                                 String task,
                                 String executorResult,
                                 List<Message> messages,
                                 AgentState executorState,
                                 int stepNo) {
        this(query, task, executorResult, messages, executorState, stepNo, "");
    }

    public PlanEvaluationRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        currentDate = currentDate == null ? "" : currentDate;
    }
}
