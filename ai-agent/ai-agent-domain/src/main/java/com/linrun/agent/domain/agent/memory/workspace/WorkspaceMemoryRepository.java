package com.linrun.agent.domain.agent.memory.workspace;

import java.util.List;

/** Storage abstraction keeps Workspace Memory independent from vector/RAG retrieval. */
public interface WorkspaceMemoryRepository {

    WorkspaceMemoryEntry save(WorkspaceMemoryEntry entry);

    List<WorkspaceMemoryEntry> list(String tenantId, String ownerId, int limit);

    boolean delete(String tenantId, String ownerId, String memoryId);

    int clear(String tenantId, String ownerId);
}
