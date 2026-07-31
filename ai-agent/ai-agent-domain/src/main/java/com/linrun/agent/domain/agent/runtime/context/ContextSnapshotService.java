package com.linrun.agent.domain.agent.runtime.context;

import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** P70 boundary for loading and revision-pinning structured context summaries. */
@Service
public class ContextSnapshotService {

    private final ContextSnapshotRepository repository;

    public ContextSnapshotService(ObjectProvider<ContextSnapshotRepository> repositoryProvider) {
        ContextSnapshotRepository candidate = repositoryProvider.getIfAvailable();
        this.repository = candidate == null ? new InMemoryContextSnapshotRepository() : candidate;
    }

    public ContextSnapshot save(ContextSnapshot draft, long expectedRevision) {
        if (draft == null) {
            throw new IllegalArgumentException("context snapshot draft is required");
        }
        ContextSnapshot next = draft.nextRevision(expectedRevision + 1);
        return repository.compareAndSet(next, expectedRevision)
                .orElseThrow(() -> new ContextSnapshotConflictException(draft.key(), expectedRevision));
    }

    public Optional<ContextSnapshot> load(ContextSnapshotKey key) {
        return repository.find(key);
    }

    /** Ensures a run owns one initial snapshot before any role-specific projection is rendered. */
    public ContextSnapshot captureInitial(AgentContext context) {
        if (context == null) {
            throw new IllegalArgumentException("agent context is required");
        }
        ContextSnapshotKey key = keyFrom(context);
        Optional<ContextSnapshot> existing = load(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        ContextSnapshot draft = new ContextSnapshot(key, 0, context.getQuery(), List.of(), List.of(), List.of(),
                List.of(), context.getTask() == null ? List.of() : List.of(context.getTask()), List.of(),
                "deterministic", "p70", "", true, 0, 0);
        try {
            return save(draft, 0);
        } catch (ContextSnapshotConflictException ignored) {
            return load(key).orElseThrow(() -> ignored);
        }
    }

    public ContextSnapshotKey keyFrom(AgentContext context) {
        AgentRunState state = context.getAgentRunState();
        return new ContextSnapshotKey(
                valueOr(context.getTenantId(), "default"),
                String.valueOf(context.getOwnerId() == null ? 0L : context.getOwnerId()),
                valueOr(context.getSessionId(), "request:" + valueOr(context.getRequestId(), "unknown")),
                state == null || state.getRunId() == null ? 0L : state.getRunId());
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
