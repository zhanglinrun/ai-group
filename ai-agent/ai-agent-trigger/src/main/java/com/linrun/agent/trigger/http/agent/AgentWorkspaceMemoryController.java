package com.linrun.agent.trigger.http.agent;

import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemoryEntry;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemoryExport;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemoryService;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemorySuggestion;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Product API for explicit, owner-scoped P70 Workspace Memory. */
@RestController
@RequestMapping("/api/agent/workspace-memory")
@RequiredArgsConstructor
public class AgentWorkspaceMemoryController {

    private static final String DEFAULT_TENANT = "default";

    private final WorkspaceMemoryService workspaceMemoryService;

    @PostMapping
    public Response<MemoryView> remember(@RequestBody RememberRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("workspace memory is required");
        }
        WorkspaceMemoryEntry saved = workspaceMemoryService.remember(DEFAULT_TENANT, ownerId(), request.topic(),
                request.content(), request.confidence(), expiry(request.ttlDays()));
        return success(MemoryView.from(saved));
    }

    /** Curator output is preview-only and cannot become durable memory through this endpoint. */
    @PostMapping("/suggestions")
    public Response<SuggestionView> suggest(@RequestBody SuggestionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("workspace memory suggestion is required");
        }
        WorkspaceMemorySuggestion suggestion = workspaceMemoryService.suggest(DEFAULT_TENANT, ownerId(),
                request.topic(), request.content(), request.confidence());
        return success(new SuggestionView(suggestion.topic(), suggestion.content(), suggestion.confidence(), true));
    }

    @GetMapping
    public Response<List<MemoryView>> list(@RequestParam(value = "topic", required = false) List<String> topics) {
        return success(workspaceMemoryService.loadForRun(DEFAULT_TENANT, ownerId(), topics).stream()
                .map(MemoryView::from)
                .toList());
    }

    @DeleteMapping("/{memoryId}")
    public Response<Boolean> delete(@PathVariable String memoryId) {
        return success(workspaceMemoryService.delete(DEFAULT_TENANT, ownerId(), memoryId));
    }

    @GetMapping("/export")
    public Response<ExportView> export() {
        WorkspaceMemoryExport export = workspaceMemoryService.export(DEFAULT_TENANT, ownerId());
        return success(new ExportView(export.tenantId(), export.ownerId(), export.entries().stream()
                .map(MemoryView::from).toList()));
    }

    @DeleteMapping
    public Response<Integer> clear() {
        return success(workspaceMemoryService.clear(DEFAULT_TENANT, ownerId()));
    }

    private String ownerId() {
        return OwnerRequestContext.requireOwnerIdAsString();
    }

    private Long expiry(Integer ttlDays) {
        if (ttlDays == null) {
            return null;
        }
        if (ttlDays < 1 || ttlDays > 365) {
            throw new IllegalArgumentException("ttlDays must be between 1 and 365");
        }
        return Instant.now().plusSeconds(ttlDays.longValue() * 86_400L).toEpochMilli();
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    public record RememberRequest(String topic, String content, double confidence, Integer ttlDays) {
    }

    public record SuggestionRequest(String topic, String content, double confidence) {
    }

    public record MemoryView(String id, String topic, String content, String source, double confidence,
                             long revision, Long createdAtEpochMillis, Long expiresAtEpochMillis) {
        static MemoryView from(WorkspaceMemoryEntry entry) {
            return new MemoryView(entry.id(), entry.topic(), entry.content(), entry.source().name(),
                    entry.confidence(), entry.revision(), entry.createdAtEpochMillis(), entry.expiresAtEpochMillis());
        }
    }

    public record SuggestionView(String topic, String content, double confidence, boolean pendingConfirmation) {
    }

    public record ExportView(String tenantId, String ownerId, List<MemoryView> entries) {
    }
}
