package com.linrun.agent.domain.agent.runtime.profile;

import java.util.List;

/** Immutable role snapshot resolved before one AgentRuntime run starts. */
public record ResolvedAgentProfile(
        String agentId,
        String agentName,
        String description,
        String procedurePrompt,
        List<String> clientIds
) {
    public ResolvedAgentProfile {
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
    }

    public String trustedPrompt() {
        StringBuilder prompt = new StringBuilder();
        if (agentName != null && !agentName.isBlank()) prompt.append("\n\n# 当前角色\n").append(agentName.trim());
        if (description != null && !description.isBlank()) prompt.append("\n").append(description.trim());
        if (procedurePrompt != null && !procedurePrompt.isBlank()) {
            prompt.append("\n\n# 角色执行规程\n").append(procedurePrompt.trim())
                    .append("\n按统一 Agent Loop 执行；多步骤任务使用 todo_write，不得启动平行运行时。");
        }
        return prompt.toString();
    }
}
