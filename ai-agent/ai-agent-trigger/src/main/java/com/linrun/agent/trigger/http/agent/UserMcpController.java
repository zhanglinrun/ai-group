package com.linrun.agent.trigger.http.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.runtime.tool.mcp.user.UserMcpConfig;
import com.linrun.agent.domain.agent.runtime.tool.mcp.user.UserMcpExtensionService;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;

import java.util.List;

@RestController
@RequestMapping("/api/agent/extensions/mcps")
@RequiredArgsConstructor
public class UserMcpController {

    private final UserMcpExtensionService userMcpExtensionService;

    @GetMapping
    public Response<List<UserMcpConfig>> list() {
        return success(userMcpExtensionService.list(ownerId()));
    }

    @PostMapping
    public Response<UserMcpConfig> save(@RequestBody UserMcpConfig config) {
        return execute(() -> userMcpExtensionService.save(ownerId(), config));
    }

    @PutMapping("/{id}/enabled")
    public Response<UserMcpConfig> setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
        return execute(() -> userMcpExtensionService.setEnabled(ownerId(), id, enabled));
    }

    @DeleteMapping("/{id}")
    public Response<Boolean> delete(@PathVariable String id) {
        try {
            userMcpExtensionService.delete(ownerId(), id);
            return success(Boolean.TRUE);
        } catch (RuntimeException e) {
            return failure(e.getMessage());
        }
    }

    private String ownerId() {
        return OwnerRequestContext.requireOwnerIdAsString();
    }

    private <T> Response<T> execute(java.util.function.Supplier<T> action) {
        try {
            return success(action.get());
        } catch (RuntimeException e) {
            return failure(e.getMessage());
        }
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private <T> Response<T> failure(String message) {
        return Response.<T>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(message)
                .build();
    }
}
