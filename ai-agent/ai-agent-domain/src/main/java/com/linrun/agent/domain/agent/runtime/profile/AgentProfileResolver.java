package com.linrun.agent.domain.agent.runtime.profile;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.adapter.repository.IAgentRepository;
import com.linrun.agent.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import com.linrun.agent.domain.agent.model.valobj.AiAgentVO;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Resolves persisted fixed-role configuration into one trusted Harness profile. */
@Service
@RequiredArgsConstructor
public class AgentProfileResolver {
    private final IAgentRepository repository;

    public ResolvedAgentProfile resolve(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        AiAgentVO role = repository.queryAvailableFixRoleByAgentId(agentId.trim());
        if (role == null) throw new IllegalArgumentException("角色不可用或不存在: " + agentId);
        List<AiAgentClientFlowConfigVO> steps = repository.queryAiAgentClientsByAgentId(agentId.trim());
        List<AiAgentClientFlowConfigVO> ordered = steps == null ? List.of() : steps.stream()
                .filter(item -> item != null && item.getClientId() != null && !item.getClientId().isBlank())
                .sorted(Comparator.comparing(item -> item.getSequence() == null ? Integer.MAX_VALUE : item.getSequence()))
                .toList();
        AtomicInteger index = new AtomicInteger();
        String procedure = ordered.stream().filter(item -> item.getStepPrompt() != null && !item.getStepPrompt().isBlank())
                .map(item -> (index.incrementAndGet()) + ". " + item.getStepPrompt().trim())
                .collect(java.util.stream.Collectors.joining("\n"));
        return new ResolvedAgentProfile(role.getAgentId(), role.getAgentName(), role.getDescription(), procedure,
                ordered.stream().map(AiAgentClientFlowConfigVO::getClientId).distinct().toList());
    }
}
