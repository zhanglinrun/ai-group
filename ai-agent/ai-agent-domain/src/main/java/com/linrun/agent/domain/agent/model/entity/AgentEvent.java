package com.linrun.agent.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentEvent {
    private String taskId;
    private String messageType; // run_started, phase_changed, todo_snapshot, tool_call, tool_result, verification_result, result
    private Map<String, Object> resultMap;
    private String messageId;
    private Boolean finish;
    private Boolean isFinal;
}
