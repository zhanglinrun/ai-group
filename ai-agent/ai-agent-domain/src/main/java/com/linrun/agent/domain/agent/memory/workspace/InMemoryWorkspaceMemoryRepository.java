package com.linrun.agent.domain.agent.memory.workspace;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local/test fallback. Production wiring supplies the PostgreSQL implementation. */
public final class InMemoryWorkspaceMemoryRepository implements WorkspaceMemoryRepository {

    private final Map<String, WorkspaceMemoryEntry> entries = new LinkedHashMap<>();

    @Override
    public synchronized WorkspaceMemoryEntry save(WorkspaceMemoryEntry entry) {
        entries.put(entry.id(), entry);
        return entry;
    }

    @Override
    public synchronized List<WorkspaceMemoryEntry> list(String tenantId, String ownerId, int limit) {
        long now = System.currentTimeMillis();
        return entries.values().stream()
                .filter(entry -> entry.tenantId().equals(tenantId) && entry.ownerId().equals(ownerId))
                .filter(entry -> !entry.isExpired(now))
                .sorted(Comparator.comparingLong(WorkspaceMemoryEntry::createdAtEpochMillis).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized boolean delete(String tenantId, String ownerId, String memoryId) {
        WorkspaceMemoryEntry existing = entries.get(memoryId);
        if (existing == null || !existing.tenantId().equals(tenantId) || !existing.ownerId().equals(ownerId)) {
            return false;
        }
        entries.remove(memoryId);
        return true;
    }

    @Override
    public synchronized int clear(String tenantId, String ownerId) {
        List<String> ids = entries.values().stream()
                .filter(entry -> entry.tenantId().equals(tenantId) && entry.ownerId().equals(ownerId))
                .map(WorkspaceMemoryEntry::id)
                .toList();
        ids.forEach(entries::remove);
        return ids.size();
    }
}
