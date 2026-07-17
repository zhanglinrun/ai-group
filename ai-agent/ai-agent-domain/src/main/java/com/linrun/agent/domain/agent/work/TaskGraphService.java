package com.linrun.agent.domain.agent.work;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 持久任务图的领域语义：依赖、认领、状态迁移与完成时自动解锁。 */
@Service
public class TaskGraphService {
    private final WorkStateRepository repository;

    public TaskGraphService() {
        this(new InMemoryWorkStateRepository());
    }

    @Autowired
    public TaskGraphService(WorkStateRepository repository) {
        this.repository = repository;
    }

    public WorkTask create(String ownerId, String workspaceId, String subject, String description,
                           String activeForm, List<String> blockedBy, Map<String, Object> metadata) {
        require(ownerId, workspaceId, subject);
        List<String> dependencies = normalizeIds(blockedBy);
        dependencies.forEach(id -> requireTask(ownerId, workspaceId, id));
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        WorkTask task = new WorkTask(id, workspaceId, ownerId, subject.trim(), description,
                activeForm, WorkTask.Status.PENDING, null, List.of(), dependencies, metadata, now, now, null, 0);
        WorkTask saved = repository.createTask(task);
        linkBlocks(saved);
        appendEvent(saved, "TASK_CREATED", ownerId, Map.of("subject", saved.subject()));
        return saved;
    }

    public List<WorkTask> list(String ownerId, String workspaceId) {
        require(ownerId, workspaceId, "_");
        return repository.findTasks(ownerId, workspaceId);
    }

    public WorkTask get(String ownerId, String workspaceId, String taskId) {
        WorkTask task = requireTask(ownerId, workspaceId, taskId);
        return task;
    }

    public WorkTask claim(String ownerId, String workspaceId, String taskId, String assignee) {
        WorkTask task = requireTask(ownerId, workspaceId, taskId);
        if (!task.isUnblocked() && task.status() != WorkTask.Status.IN_PROGRESS) {
            throw new IllegalStateException("任务仍被依赖阻塞，不能认领");
        }
        if (task.taskOwner() != null && !task.taskOwner().equals(assignee)) {
            throw new IllegalStateException("任务已被其他执行者认领");
        }
        WorkTask claimed = replace(task, WorkTask.Status.IN_PROGRESS, assignee, task.blockedBy(), null);
        appendEvent(claimed, "TASK_CLAIMED", assignee, Map.of());
        return claimed;
    }

    public WorkTask updateStatus(String ownerId, String workspaceId, String taskId,
                                 WorkTask.Status status, String assignee, String note) {
        WorkTask task = requireTask(ownerId, workspaceId, taskId);
        if (status == null) throw new IllegalArgumentException("status不能为空");
        if (status == WorkTask.Status.IN_PROGRESS && !task.isUnblocked() && task.status() != WorkTask.Status.IN_PROGRESS) {
            throw new IllegalStateException("任务仍被依赖阻塞，不能开始");
        }
        if (status == WorkTask.Status.COMPLETED && !task.blockedBy().isEmpty()) {
            throw new IllegalStateException("任务仍被依赖阻塞，不能完成");
        }
        String nextOwner = assignee == null || assignee.isBlank() ? task.taskOwner() : assignee;
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(task.metadata());
        if (note != null && !note.isBlank()) metadata.put("lastNote", note);
        WorkTask updated = replace(task, status, nextOwner, task.blockedBy(), metadata);
        appendEvent(updated, "TASK_" + status.name(), nextOwner == null ? ownerId : nextOwner,
                note == null || note.isBlank() ? Map.of() : Map.of("note", note));
        if (status == WorkTask.Status.COMPLETED) unlockDependents(updated);
        return updated;
    }

    public List<WorkTask> ready(String ownerId, String workspaceId) {
        return list(ownerId, workspaceId).stream().filter(WorkTask::isUnblocked).toList();
    }

    private WorkTask replace(WorkTask task, WorkTask.Status status, String assignee,
                             List<String> blockedBy, Map<String, Object> metadata) {
        Instant now = Instant.now();
        return repository.saveTask(new WorkTask(task.id(), task.workspaceId(), task.ownerId(), task.subject(), task.description(),
                task.activeForm(), status, assignee, task.blocks(), blockedBy,
                metadata == null ? task.metadata() : metadata, task.createdAt(), now,
                status == WorkTask.Status.COMPLETED ? now : task.completedAt(), task.version()), task.version());
    }

    private void linkBlocks(WorkTask task) {
        for (String dependencyId : task.blockedBy()) {
            WorkTask dependency = requireTask(task.ownerId(), task.workspaceId(), dependencyId);
            List<String> blocks = new ArrayList<>(dependency.blocks());
            if (!blocks.contains(task.id())) blocks.add(task.id());
            repository.saveTask(new WorkTask(dependency.id(), dependency.workspaceId(), dependency.ownerId(), dependency.subject(),
                    dependency.description(), dependency.activeForm(), dependency.status(), dependency.taskOwner(),
                    blocks, dependency.blockedBy(), dependency.metadata(), dependency.createdAt(), Instant.now(), dependency.completedAt(),
                    dependency.version()), dependency.version());
        }
    }

    private void unlockDependents(WorkTask completed) {
        repository.findTasks(completed.ownerId(), completed.workspaceId()).stream()
                .filter(t -> t.blockedBy().contains(completed.id())).forEach(t -> {
            List<String> remaining = t.blockedBy().stream().filter(id -> !id.equals(completed.id())).toList();
            WorkTask unlocked = repository.saveTask(new WorkTask(t.id(), t.workspaceId(), t.ownerId(), t.subject(), t.description(), t.activeForm(),
                    t.status(), t.taskOwner(), t.blocks(), remaining, t.metadata(), t.createdAt(), Instant.now(), t.completedAt(), t.version()),
                    t.version());
            if (remaining.isEmpty()) appendEvent(unlocked, "TASKS_UNBLOCKED", completed.taskOwner(), Map.of("blockerTaskId", completed.id()));
        });
    }

    private WorkTask requireTask(String ownerId, String workspaceId, String taskId) {
        return repository.findTask(ownerId, workspaceId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在或无权访问"));
    }

    public List<TaskGraphEvent> events(String ownerId, String workspaceId, String afterEventUid, int limit) {
        return repository.findEvents(ownerId, workspaceId, afterEventUid, limit);
    }

    private void appendEvent(WorkTask task, String type, String actorId, Map<String, Object> payload) {
        repository.appendEvent(new TaskGraphEvent(UUID.randomUUID().toString(), task.workspaceId(), task.id(),
                type, actorId == null ? task.ownerId() : actorId, payload, Instant.now()));
    }

    private static List<String> normalizeIds(List<String> ids) {
        return ids == null ? List.of() : List.copyOf(new LinkedHashSet<>(ids.stream().filter(id -> id != null && !id.isBlank()).toList()));
    }

    private static void require(String ownerId, String workspaceId, String subject) {
        if (ownerId == null || ownerId.isBlank() || workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("工作区参数不能为空");
        if (subject == null || subject.isBlank() || "_".equals(subject)) return;
    }
}
