package com.linrun.agent.domain.agent.work;

import java.util.List;
import java.util.Optional;

/** Persistence port for the Work control plane. */
public interface WorkStateRepository {
    Workspace saveWorkspace(Workspace workspace);

    List<Workspace> findWorkspaces(String ownerId);

    Optional<Workspace> findWorkspace(String ownerId, String workspaceId);

    void deleteWorkspace(String ownerId, String workspaceId);

    WorkTask createTask(WorkTask task);

    Optional<WorkTask> findTask(String ownerId, String workspaceId, String taskId);

    List<WorkTask> findTasks(String ownerId, String workspaceId);

    /** Optimistic compare-and-set update. expectedVersion is -1 for an unconditional insert. */
    WorkTask saveTask(WorkTask task, long expectedVersion);

    void appendEvent(TaskGraphEvent event);

    List<TaskGraphEvent> findEvents(String ownerId, String workspaceId, String afterEventUid, int limit);
}
