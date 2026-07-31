package com.linrun.agent.domain.agent.memory.workspace;

import java.util.List;

/** Owner-visible export; callers must never supply a different owner identity. */
public record WorkspaceMemoryExport(String tenantId, String ownerId, List<WorkspaceMemoryEntry> entries) {
    public WorkspaceMemoryExport {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
