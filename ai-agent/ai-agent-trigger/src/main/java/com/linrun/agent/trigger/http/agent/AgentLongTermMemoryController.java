package com.linrun.agent.trigger.http.agent;

import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.memory.LongTermMemoryEntry;
import com.linrun.agent.domain.agent.memory.LongTermMemoryPreference;
import com.linrun.agent.domain.agent.memory.LongTermMemoryService;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Normal authenticated product API for cross-session-memory consent and management. */
@RestController
@RequestMapping("/api/agent/memories")
@RequiredArgsConstructor
public class AgentLongTermMemoryController {

    private final LongTermMemoryService longTermMemoryService;

    @GetMapping("/preference")
    public Response<Map<String, Object>> preference() {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        return success(preferenceView(longTermMemoryService.preference(ownerId)));
    }

    @PutMapping("/preference")
    public Response<Map<String, Object>> updatePreference(@RequestBody PreferenceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("memory preference is required");
        }
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        LongTermMemoryPreference updated = longTermMemoryService.updatePreference(
                new LongTermMemoryPreference(ownerId, request.enabled(), request.retentionDays(), null));
        return success(preferenceView(updated));
    }

    @GetMapping("/entries")
    public Response<List<MemoryEntryView>> entries(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        List<MemoryEntryView> values = longTermMemoryService.listEntries(ownerId, limit).stream()
                .map(MemoryEntryView::from)
                .toList();
        return success(values);
    }

    @DeleteMapping("/entries/{memoryId}")
    public Response<Boolean> delete(@PathVariable String memoryId) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        return success(longTermMemoryService.delete(ownerId, memoryId));
    }

    private Map<String, Object> preferenceView(LongTermMemoryPreference preference) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", preference.enabled());
        view.put("retentionDays", preference.retentionDays());
        view.put("updatedAt", preference.updatedAt());
        return view;
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    public record PreferenceRequest(boolean enabled, int retentionDays) {
    }

    public record MemoryEntryView(String id, String sessionId, String requestId, String type,
                                  String content, String source, double confidence, long version,
                                  Long createdAtEpochMillis, Long expiresAtEpochMillis) {
        static MemoryEntryView from(LongTermMemoryEntry entry) {
            return new MemoryEntryView(entry.getId(), entry.getSessionId(), entry.getRequestId(),
                    String.valueOf(entry.getType()), entry.getContent(), entry.getSource(), entry.getConfidence(),
                    entry.getVersion(), entry.getCreatedAtEpochMillis(), entry.getExpiresAtEpochMillis());
        }
    }
}
