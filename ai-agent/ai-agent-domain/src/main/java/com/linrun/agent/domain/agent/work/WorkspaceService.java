package com.linrun.agent.domain.agent.work;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作区应用服务。当前使用进程内存储作为可替换的 durable repository 边界，
 * 避免把产品 API 绑定到实现细节；下一步可无损替换 JDBC/Redis adapter。
 */
@Service
public class WorkspaceService {
    private final WorkStateRepository repository;

    public WorkspaceService() {
        this(new InMemoryWorkStateRepository());
    }

    @Autowired
    public WorkspaceService(WorkStateRepository repository) {
        this.repository = repository;
    }

    public Workspace create(String ownerId, String name, String instructions, Map<String, Object> toolPolicy) {
        requireOwner(ownerId);
        String normalized = name == null || name.isBlank() ? "新工作区" : name.trim();
        Instant now = Instant.now();
        Workspace workspace = new Workspace(UUID.randomUUID().toString(), ownerId, normalized,
                instructions == null ? "" : instructions.trim(), toolPolicy, now, now);
        Workspace saved = repository.saveWorkspace(workspace);
        repository.appendEvent(new TaskGraphEvent(UUID.randomUUID().toString(), saved.id(), null,
                "WORKSPACE_CREATED", ownerId, Map.of("name", saved.name()), now));
        return saved;
    }

    public List<Workspace> list(String ownerId) {
        requireOwner(ownerId);
        return repository.findWorkspaces(ownerId);
    }

    public Workspace getRequired(String ownerId, String workspaceId) {
        requireOwner(ownerId);
        return repository.findWorkspace(ownerId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("工作区不存在或无权访问"));
    }

    public Workspace update(String ownerId, String workspaceId, String name, String instructions,
                            Map<String, Object> toolPolicy) {
        Workspace current = getRequired(ownerId, workspaceId);
        Workspace updated = new Workspace(current.id(), current.ownerId(),
                name == null || name.isBlank() ? current.name() : name.trim(),
                instructions == null ? current.instructions() : instructions.trim(),
                toolPolicy == null ? current.toolPolicy() : toolPolicy, current.createdAt(), Instant.now());
        Workspace saved = repository.saveWorkspace(updated);
        repository.appendEvent(new TaskGraphEvent(UUID.randomUUID().toString(), saved.id(), null,
                "WORKSPACE_UPDATED", ownerId, Map.of("name", saved.name()), saved.updatedAt()));
        return saved;
    }

    public void delete(String ownerId, String workspaceId) {
        getRequired(ownerId, workspaceId);
        repository.deleteWorkspace(ownerId, workspaceId);
    }

    private static void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId不能为空");
    }
}
