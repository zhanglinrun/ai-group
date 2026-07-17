package com.linrun.agent.domain.agent.work;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test/dev adapter. Production wiring replaces it with the JDBC adapter. */
public class InMemoryWorkStateRepository implements WorkStateRepository {
    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, WorkTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, TaskGraphEvent> events = new ConcurrentHashMap<>();

    @Override
    public Workspace saveWorkspace(Workspace workspace) { workspaces.put(workspace.id(), workspace); return workspace; }

    @Override
    public List<Workspace> findWorkspaces(String ownerId) {
        return workspaces.values().stream().filter(item -> ownerId.equals(item.ownerId()))
                .sorted(Comparator.comparing(Workspace::updatedAt).reversed()).toList();
    }

    @Override
    public Optional<Workspace> findWorkspace(String ownerId, String workspaceId) {
        return Optional.ofNullable(workspaces.get(workspaceId)).filter(item -> ownerId.equals(item.ownerId()));
    }

    @Override
    public void deleteWorkspace(String ownerId, String workspaceId) {
        findWorkspace(ownerId, workspaceId).ifPresent(item -> {
            workspaces.remove(workspaceId);
            tasks.values().removeIf(task -> workspaceId.equals(task.workspaceId()) && ownerId.equals(task.ownerId()));
        });
    }

    @Override
    public WorkTask createTask(WorkTask task) { tasks.put(task.id(), task); return task; }

    @Override
    public Optional<WorkTask> findTask(String ownerId, String workspaceId, String taskId) {
        return Optional.ofNullable(tasks.get(taskId)).filter(item -> ownerId.equals(item.ownerId()) && workspaceId.equals(item.workspaceId()));
    }

    @Override
    public List<WorkTask> findTasks(String ownerId, String workspaceId) {
        return tasks.values().stream().filter(item -> ownerId.equals(item.ownerId()) && workspaceId.equals(item.workspaceId()))
                .sorted(Comparator.comparing(WorkTask::createdAt)).toList();
    }

    @Override
    public synchronized WorkTask saveTask(WorkTask task, long expectedVersion) {
        WorkTask current = tasks.get(task.id());
        if (expectedVersion >= 0 && (current == null || current.version() != expectedVersion)) {
            throw new IllegalStateException("任务版本冲突，请刷新后重试");
        }
        WorkTask saved = new WorkTask(task.id(), task.workspaceId(), task.ownerId(), task.subject(), task.description(),
                task.activeForm(), task.status(), task.taskOwner(), task.blocks(), task.blockedBy(), task.metadata(),
                task.createdAt(), Instant.now(), task.completedAt(), expectedVersion < 0 ? 0 : expectedVersion + 1);
        tasks.put(saved.id(), saved);
        return saved;
    }

    @Override
    public void appendEvent(TaskGraphEvent event) { events.put(event.eventUid(), event); }

    @Override
    public List<TaskGraphEvent> findEvents(String ownerId, String workspaceId, String afterEventUid, int limit) {
        return events.values().stream().filter(event -> workspaceId.equals(event.workspaceId()))
                .limit(Math.max(1, Math.min(limit, 200))).toList();
    }
}
