package com.linrun.agent.trigger.http.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.work.TaskGraphService;
import com.linrun.agent.domain.agent.work.WorkTask;
import com.linrun.agent.domain.agent.work.Workspace;
import com.linrun.agent.domain.agent.work.WorkspaceService;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;

import java.util.List;
import java.util.Map;

/** ChatGPT Work 风格工作区与持久任务图控制面。运行时 Todo 不经过此 API。 */
@RestController
@RequestMapping("/api/agent/work")
@RequiredArgsConstructor
public class AgentWorkController {
    private final WorkspaceService workspaceService;
    private final TaskGraphService taskGraphService;

    @GetMapping("/workspaces")
    public Response<List<Workspace>> listWorkspaces() {
        return success(workspaceService.list(owner()));
    }

    @PostMapping("/workspaces")
    public Response<Workspace> createWorkspace(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = body == null ? Map.of() : body;
        return execute(() -> workspaceService.create(owner(), string(input, "name"),
                string(input, "instructions"), map(input.get("toolPolicy"))));
    }

    @GetMapping("/workspaces/{workspaceId}")
    public Response<Workspace> getWorkspace(@PathVariable String workspaceId) {
        return execute(() -> workspaceService.getRequired(owner(), workspaceId));
    }

    @PatchMapping("/workspaces/{workspaceId}")
    public Response<Workspace> updateWorkspace(@PathVariable String workspaceId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = body == null ? Map.of() : body;
        return execute(() -> workspaceService.update(owner(), workspaceId, string(input, "name"),
                string(input, "instructions"), input.containsKey("toolPolicy") ? map(input.get("toolPolicy")) : null));
    }

    @DeleteMapping("/workspaces/{workspaceId}")
    public Response<Boolean> deleteWorkspace(@PathVariable String workspaceId) {
        return execute(() -> { workspaceService.delete(owner(), workspaceId); return Boolean.TRUE; });
    }

    @GetMapping("/workspaces/{workspaceId}/tasks")
    public Response<List<WorkTask>> listTasks(@PathVariable String workspaceId) {
        return execute(() -> { ensureWorkspace(workspaceId); return taskGraphService.list(owner(), workspaceId); });
    }

    @GetMapping("/workspaces/{workspaceId}/tasks/ready")
    public Response<List<WorkTask>> readyTasks(@PathVariable String workspaceId) {
        return execute(() -> { ensureWorkspace(workspaceId); return taskGraphService.ready(owner(), workspaceId); });
    }

    @GetMapping("/workspaces/{workspaceId}/events")
    public Response<List<com.linrun.agent.domain.agent.work.TaskGraphEvent>> events(
            @PathVariable String workspaceId,
            @RequestParam(required = false) String afterEventUid,
            @RequestParam(defaultValue = "100") int limit) {
        return execute(() -> { ensureWorkspace(workspaceId); return taskGraphService.events(owner(), workspaceId, afterEventUid, limit); });
    }

    @PostMapping("/workspaces/{workspaceId}/tasks")
    public Response<WorkTask> createTask(@PathVariable String workspaceId,
                                         @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = body == null ? Map.of() : body;
        return execute(() -> { ensureWorkspace(workspaceId); return taskGraphService.create(owner(), workspaceId, string(input, "subject"),
                string(input, "description"), string(input, "activeForm"), strings(input.get("blockedBy")),
                map(input.get("metadata"))); });
    }

    @PostMapping("/workspaces/{workspaceId}/tasks/{taskId}/claim")
    public Response<WorkTask> claim(@PathVariable String workspaceId, @PathVariable String taskId,
                                    @RequestBody(required = false) Map<String, Object> body) {
        String assignee = string(body == null ? Map.of() : body, "assignee");
        return execute(() -> { ensureWorkspace(workspaceId); return taskGraphService.claim(owner(), workspaceId, taskId,
                assignee == null || assignee.isBlank() ? owner() : assignee);
        });
    }

    @PatchMapping("/workspaces/{workspaceId}/tasks/{taskId}")
    public Response<WorkTask> updateTask(@PathVariable String workspaceId, @PathVariable String taskId,
                                         @RequestBody Map<String, Object> body) {
        String status = string(body, "status");
        WorkTask.Status next = status == null ? null : WorkTask.Status.valueOf(status.toUpperCase());
        return execute(() -> { ensureWorkspace(workspaceId); return taskGraphService.updateStatus(owner(), workspaceId, taskId, next,
                string(body, "assignee"), string(body, "note")); });
    }

    private String owner() { return OwnerRequestContext.requireOwnerIdAsString(); }
    private void ensureWorkspace(String workspaceId) { workspaceService.getRequired(owner(), workspaceId); }
    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key); return value == null ? null : String.valueOf(value);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
    private static <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }
    private static <T> Response<T> failure(String message) {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(message == null ? "操作失败" : message).build();
    }
    private static <T> Response<T> execute(java.util.function.Supplier<T> action) {
        try { return success(action.get()); } catch (RuntimeException e) { return failure(e.getMessage()); }
    }
}
