package com.linrun.agent.domain.agent.runtime.work;

import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;

/** Immutable identity of the Todo item that was active when a tool call began. */
public record TodoStepEvidenceScope(int stepIndex,
                                    long activationId,
                                    TodoEvidencePolicy evidencePolicy) {
}
