package com.linrun.agent.infrastructure.adapter.repository;

import com.linrun.agent.types.common.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import com.linrun.agent.domain.agent.work.TaskGraphEvent;
import com.linrun.agent.domain.agent.work.WorkStateRepository;
import com.linrun.agent.domain.agent.work.WorkTask;
import com.linrun.agent.domain.agent.work.Workspace;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MySQL adapter for Workspaces, TaskGraph nodes and the event cursor. */
@Repository
public class JdbcWorkStateRepository implements WorkStateRepository {
    private final JdbcTemplate jdbc;

    public JdbcWorkStateRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Keeps a fresh local profile usable before the SQL bundle is manually applied. */
    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("create table if not exists agent_workspace (id bigint not null auto_increment, workspace_uid varchar(64) not null, owner_id varchar(64) not null, name varchar(255) not null, instructions text, tool_policy_json json, created_at datetime not null default current_timestamp, updated_at datetime not null default current_timestamp on update current_timestamp, primary key (id), unique key uk_workspace_uid (workspace_uid), key idx_workspace_owner (owner_id,updated_at)) engine=InnoDB default charset=utf8mb4");
        jdbc.execute("create table if not exists agent_task_node (id bigint not null auto_increment, task_uid varchar(64) not null, workspace_uid varchar(64) not null, owner_id varchar(64) not null, subject varchar(255) not null, description text, active_form varchar(255), status varchar(32) not null default 'PENDING', assignee varchar(128), metadata_json json, version int not null default 0, created_at datetime not null default current_timestamp, updated_at datetime not null default current_timestamp on update current_timestamp, completed_at datetime null, primary key (id), unique key uk_task_uid (task_uid), key idx_task_workspace (workspace_uid,status,updated_at)) engine=InnoDB default charset=utf8mb4");
        jdbc.execute("create table if not exists agent_task_dependency (blocker_task_uid varchar(64) not null, blocked_task_uid varchar(64) not null, created_at datetime not null default current_timestamp, primary key (blocker_task_uid,blocked_task_uid), key idx_dependency_blocked (blocked_task_uid)) engine=InnoDB default charset=utf8mb4");
        jdbc.execute("create table if not exists agent_task_event (id bigint not null auto_increment, event_uid varchar(64) not null, workspace_uid varchar(64) not null, task_uid varchar(64) null, event_type varchar(64) not null, actor_id varchar(64) not null, payload_json json, created_at datetime not null default current_timestamp, primary key (id), unique key uk_task_event_uid (event_uid), key idx_task_event_cursor (workspace_uid,id)) engine=InnoDB default charset=utf8mb4");
    }

    @Override
    public Workspace saveWorkspace(Workspace workspace) {
        int changed = jdbc.update("update agent_workspace set name=?, instructions=?, tool_policy_json=?, updated_at=? where workspace_uid=? and owner_id=?",
                workspace.name(), workspace.instructions(), JsonUtils.toJson(workspace.toolPolicy()), Timestamp.from(workspace.updatedAt()), workspace.id(), workspace.ownerId());
        if (changed == 0) {
            jdbc.update("insert into agent_workspace(workspace_uid,owner_id,name,instructions,tool_policy_json,created_at,updated_at) values (?,?,?,?,?,?,?)",
                    workspace.id(), workspace.ownerId(), workspace.name(), workspace.instructions(), JsonUtils.toJson(workspace.toolPolicy()),
                    Timestamp.from(workspace.createdAt()), Timestamp.from(workspace.updatedAt()));
        }
        return workspace;
    }

    @Override
    public List<Workspace> findWorkspaces(String ownerId) {
        return jdbc.query("select workspace_uid,owner_id,name,instructions,tool_policy_json,created_at,updated_at from agent_workspace where owner_id=? order by updated_at desc",
                (rs, rowNum) -> workspace(rs), ownerId);
    }

    @Override
    public Optional<Workspace> findWorkspace(String ownerId, String workspaceId) {
        List<Workspace> result = jdbc.query("select workspace_uid,owner_id,name,instructions,tool_policy_json,created_at,updated_at from agent_workspace where owner_id=? and workspace_uid=?",
                (rs, rowNum) -> workspace(rs), ownerId, workspaceId);
        return result.stream().findFirst();
    }

    @Override
    public void deleteWorkspace(String ownerId, String workspaceId) {
        jdbc.update("delete from agent_task_dependency where blocker_task_uid in (select task_uid from agent_task_node where owner_id=? and workspace_uid=?) or blocked_task_uid in (select task_uid from agent_task_node where owner_id=? and workspace_uid=?)",
                ownerId, workspaceId, ownerId, workspaceId);
        jdbc.update("delete from agent_task_event where workspace_uid=?", workspaceId);
        jdbc.update("delete from agent_task_node where owner_id=? and workspace_uid=?", ownerId, workspaceId);
        jdbc.update("delete from agent_workspace where owner_id=? and workspace_uid=?", ownerId, workspaceId);
    }

    @Override
    public WorkTask createTask(WorkTask task) {
        jdbc.update("insert into agent_task_node(task_uid,workspace_uid,owner_id,subject,description,active_form,status,assignee,metadata_json,version,created_at,updated_at,completed_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                task.id(), task.workspaceId(), task.ownerId(), task.subject(), task.description(), task.activeForm(), task.status().name(),
                task.taskOwner(), JsonUtils.toJson(task.metadata()), task.version(), Timestamp.from(task.createdAt()), Timestamp.from(task.updatedAt()),
                task.completedAt() == null ? null : Timestamp.from(task.completedAt()));
        for (String blocker : task.blockedBy()) {
            jdbc.update("insert ignore into agent_task_dependency(blocker_task_uid,blocked_task_uid) values (?,?)", blocker, task.id());
        }
        return task;
    }

    @Override
    public Optional<WorkTask> findTask(String ownerId, String workspaceId, String taskId) {
        List<WorkTask> result = jdbc.query("select task_uid,workspace_uid,owner_id,subject,description,active_form,status,assignee,metadata_json,version,created_at,updated_at,completed_at from agent_task_node where owner_id=? and workspace_uid=? and task_uid=?",
                (rs, rowNum) -> task(rs), ownerId, workspaceId, taskId);
        return result.stream().findFirst();
    }

    @Override
    public List<WorkTask> findTasks(String ownerId, String workspaceId) {
        return jdbc.query("select task_uid,workspace_uid,owner_id,subject,description,active_form,status,assignee,metadata_json,version,created_at,updated_at,completed_at from agent_task_node where owner_id=? and workspace_uid=? order by created_at",
                (rs, rowNum) -> task(rs), ownerId, workspaceId);
    }

    @Override
    public WorkTask saveTask(WorkTask task, long expectedVersion) {
        int changed = jdbc.update("update agent_task_node set status=?,assignee=?,metadata_json=?,version=version+1,updated_at=?,completed_at=? where task_uid=? and owner_id=? and workspace_uid=? and version=?",
                task.status().name(), task.taskOwner(), JsonUtils.toJson(task.metadata()), Timestamp.from(Instant.now()),
                task.completedAt() == null ? null : Timestamp.from(task.completedAt()), task.id(), task.ownerId(), task.workspaceId(), expectedVersion);
        if (changed != 1) throw new IllegalStateException("任务版本冲突，请刷新后重试");
        if (!task.blockedBy().isEmpty()) {
            jdbc.update("delete from agent_task_dependency where blocked_task_uid=?", task.id());
            for (String blocker : task.blockedBy()) jdbc.update("insert ignore into agent_task_dependency(blocker_task_uid,blocked_task_uid) values (?,?)", blocker, task.id());
        } else {
            jdbc.update("delete from agent_task_dependency where blocked_task_uid=?", task.id());
        }
        return findTask(task.ownerId(), task.workspaceId(), task.id()).orElseThrow();
    }

    @Override
    public void appendEvent(TaskGraphEvent event) {
        jdbc.update("insert into agent_task_event(event_uid,workspace_uid,task_uid,event_type,actor_id,payload_json,created_at) values (?,?,?,?,?,?,?)",
                event.eventUid(), event.workspaceId(), event.taskId(), event.eventType(), event.actorId(), JsonUtils.toJson(event.payload()), Timestamp.from(event.createdAt()));
    }

    @Override
    public List<TaskGraphEvent> findEvents(String ownerId, String workspaceId, String afterEventUid, int limit) {
        String cursor = afterEventUid == null || afterEventUid.isBlank() ? "" : afterEventUid;
        return jdbc.query("select e.event_uid,e.workspace_uid,e.task_uid,e.event_type,e.actor_id,e.payload_json,e.created_at from agent_task_event e join agent_workspace w on w.workspace_uid=e.workspace_uid where w.owner_id=? and e.workspace_uid=? and e.id > coalesce((select id from agent_task_event where event_uid=?),0) order by e.id limit ?",
                (rs, rowNum) -> new TaskGraphEvent(rs.getString("event_uid"), rs.getString("workspace_uid"), rs.getString("task_uid"),
                        rs.getString("event_type"), rs.getString("actor_id"), parseMap(rs.getString("payload_json")), rs.getTimestamp("created_at").toInstant()),
                ownerId, workspaceId, cursor, Math.max(1, Math.min(limit, 200)));
    }

    private Workspace workspace(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Workspace(rs.getString("workspace_uid"), rs.getString("owner_id"), rs.getString("name"), rs.getString("instructions"),
                parseMap(rs.getString("tool_policy_json")), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private WorkTask task(java.sql.ResultSet rs) throws java.sql.SQLException {
        String id = rs.getString("task_uid");
        String workspaceId = rs.getString("workspace_uid");
        List<String> blocks = jdbc.queryForList("select blocked_task_uid from agent_task_dependency where blocker_task_uid=?", String.class, id);
        List<String> blockedBy = jdbc.queryForList("select blocker_task_uid from agent_task_dependency where blocked_task_uid=?", String.class, id);
        return new WorkTask(id, workspaceId, rs.getString("owner_id"), rs.getString("subject"), rs.getString("description"), rs.getString("active_form"),
                WorkTask.Status.valueOf(rs.getString("status")), rs.getString("assignee"), blocks, blockedBy, parseMap(rs.getString("metadata_json")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(), rs.getLong("version"));
    }

    private static Map<String, Object> parseMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return JsonUtils.parseObject(value, new TypeReference<Map<String, Object>>() {}); }
        catch (RuntimeException ignored) { return Map.of(); }
    }
}
