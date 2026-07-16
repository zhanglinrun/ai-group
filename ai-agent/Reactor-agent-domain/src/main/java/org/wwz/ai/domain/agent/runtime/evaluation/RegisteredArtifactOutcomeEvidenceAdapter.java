package org.wwz.ai.domain.agent.runtime.evaluation;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactBinding;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactFormatter;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Converts current-round artifact-registry bindings into server-verifiable outcome evidence.
 *
 * <p>A visible artifact is required only after a tool has explicitly registered it. No task or
 * model text is inspected to decide whether an artifact should exist. Bindings from another
 * request/session or an earlier executor round are ignored.</p>
 */
public final class RegisteredArtifactOutcomeEvidenceAdapter implements PlanOutcomeEvidenceAdapter {

    private final AgentContext context;

    public RegisteredArtifactOutcomeEvidenceAdapter(AgentContext context) {
        this.context = Objects.requireNonNull(context, "agentContext must not be null");
    }

    @Override
    public List<PlanOutcomeEvidence> collect(PlanEvaluationRequest request) {
        Set<String> currentToolCallIds = currentToolCallIds(request);
        if (currentToolCallIds.isEmpty()) {
            return List.of();
        }

        List<PlanOutcomeEvidence> evidence = new ArrayList<>();
        Set<String> seenArtifactKeys = new LinkedHashSet<>();
        for (ToolArtifactBinding binding : context.getVisibleArtifactBindings()) {
            if (!belongsToCurrentRound(binding, currentToolCallIds)) {
                continue;
            }
            String artifactKey = ToolArtifactFormatter.buildArtifactKey(binding);
            if (!seenArtifactKeys.add(artifactKey)) {
                continue;
            }
            ToolArtifactSource source = binding.getSource();
            org.wwz.ai.domain.agent.runtime.dto.File file = binding.getFile();
            evidence.add(PlanOutcomeEvidence.registeredArtifact(
                    artifactKey,
                    true,
                    source.getToolCallId(),
                    file.getFileName(),
                    ToolArtifactFormatter.resolveFileUrl(file)
            ));
        }
        return List.copyOf(evidence);
    }

    private boolean belongsToCurrentRound(ToolArtifactBinding binding, Set<String> currentToolCallIds) {
        if (binding == null || binding.getSource() == null || binding.getFile() == null) {
            return false;
        }
        ToolArtifactSource source = binding.getSource();
        return currentToolCallIds.contains(source.getToolCallId())
                && StringUtils.equals(context.getRequestId(), source.getRequestId())
                && StringUtils.equals(context.getSessionId(), source.getSessionId());
    }

    private Set<String> currentToolCallIds(PlanEvaluationRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Message message : request.messages()) {
            if (message == null) {
                continue;
            }
            if (StringUtils.isNotBlank(message.getToolCallId())) {
                ids.add(message.getToolCallId());
            }
            if (message.getToolCalls() == null) {
                continue;
            }
            for (ToolCall toolCall : message.getToolCalls()) {
                if (toolCall != null && StringUtils.isNotBlank(toolCall.getId())) {
                    ids.add(toolCall.getId());
                }
            }
        }
        return ids;
    }
}
