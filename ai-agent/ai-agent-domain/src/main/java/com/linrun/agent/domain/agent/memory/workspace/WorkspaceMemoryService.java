package com.linrun.agent.domain.agent.memory.workspace;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** P70 explicit-memory service: save, bounded load, owner deletion/export and complete clear. */
@Service
public class WorkspaceMemoryService {

    private static final int MAX_LOAD_TOPICS = 3;
    private static final int MAX_LIST_LIMIT = 200;

    private final WorkspaceMemoryRepository repository;

    public WorkspaceMemoryService(ObjectProvider<WorkspaceMemoryRepository> repositoryProvider) {
        WorkspaceMemoryRepository candidate = repositoryProvider.getIfAvailable();
        this.repository = candidate == null ? new InMemoryWorkspaceMemoryRepository() : candidate;
    }

    public WorkspaceMemoryEntry remember(String tenantId,
                                         String ownerId,
                                         String topic,
                                         String content,
                                         double confidence,
                                         Long expiresAtEpochMillis) {
        requireIdentity(tenantId, ownerId);
        if (expiresAtEpochMillis != null && expiresAtEpochMillis <= Instant.now().toEpochMilli()) {
            throw new IllegalArgumentException("workspace memory expiry must be in the future");
        }
        WorkspaceMemoryEntry existing = list(tenantId, ownerId, MAX_LIST_LIMIT).stream()
                .filter(entry -> entry.topic().equalsIgnoreCase(topic == null ? "" : topic.trim()))
                .findFirst()
                .orElse(null);
        long now = Instant.now().toEpochMilli();
        WorkspaceMemoryEntry entry = new WorkspaceMemoryEntry(
                existing == null ? UUID.randomUUID().toString() : existing.id(), tenantId, ownerId, topic, content,
                WorkspaceMemorySource.EXPLICIT_USER, confidence,
                existing == null ? 1L : existing.revision() + 1L,
                existing == null ? now : existing.createdAtEpochMillis(), expiresAtEpochMillis);
        return repository.save(entry);
    }

    /** Creates a reviewable proposal and never writes it to storage. */
    public WorkspaceMemorySuggestion suggest(String tenantId, String ownerId, String topic,
                                             String content, double confidence) {
        requireIdentity(tenantId, ownerId);
        return new WorkspaceMemorySuggestion(tenantId.trim(), ownerId.trim(), topic == null ? "" : topic.trim(),
                content == null ? "" : content.trim(), Math.max(0D, Math.min(1D, confidence)));
    }

    /** At most three explicit memory topics may be injected into a single run. */
    public List<WorkspaceMemoryEntry> loadForRun(String tenantId, String ownerId, List<String> topics) {
        requireIdentity(tenantId, ownerId);
        List<String> requestedTopics = topics == null ? List.of() : topics.stream()
                .filter(topic -> topic != null && !topic.isBlank())
                .map(topic -> topic.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .limit(MAX_LOAD_TOPICS)
                .toList();
        return list(tenantId, ownerId, MAX_LIST_LIMIT).stream()
                .filter(entry -> requestedTopics.isEmpty()
                        || requestedTopics.contains(entry.topic().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingLong(WorkspaceMemoryEntry::createdAtEpochMillis).reversed())
                .limit(MAX_LOAD_TOPICS)
                .toList();
    }

    public List<WorkspaceMemoryEntry> list(String tenantId, String ownerId, int limit) {
        requireIdentity(tenantId, ownerId);
        return repository.list(tenantId.trim(), ownerId.trim(), Math.max(1, Math.min(limit, MAX_LIST_LIMIT)));
    }

    public boolean delete(String tenantId, String ownerId, String memoryId) {
        requireIdentity(tenantId, ownerId);
        return memoryId != null && !memoryId.isBlank()
                && repository.delete(tenantId.trim(), ownerId.trim(), memoryId.trim());
    }

    public int clear(String tenantId, String ownerId) {
        requireIdentity(tenantId, ownerId);
        return repository.clear(tenantId.trim(), ownerId.trim());
    }

    public WorkspaceMemoryExport export(String tenantId, String ownerId) {
        requireIdentity(tenantId, ownerId);
        return new WorkspaceMemoryExport(tenantId.trim(), ownerId.trim(), list(tenantId, ownerId, MAX_LIST_LIMIT));
    }

    private void requireIdentity(String tenantId, String ownerId) {
        if (tenantId == null || tenantId.isBlank() || ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("tenantId and ownerId are required for workspace memory");
        }
    }
}
