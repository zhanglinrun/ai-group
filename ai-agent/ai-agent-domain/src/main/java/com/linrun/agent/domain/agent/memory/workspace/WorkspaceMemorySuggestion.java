package com.linrun.agent.domain.agent.memory.workspace;

/** A curator proposal is deliberately non-durable until an explicit user action remembers it. */
public record WorkspaceMemorySuggestion(String tenantId,
                                        String ownerId,
                                        String topic,
                                        String content,
                                        double confidence) {
}
