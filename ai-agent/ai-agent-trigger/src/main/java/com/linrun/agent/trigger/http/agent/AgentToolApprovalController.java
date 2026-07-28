package com.linrun.agent.trigger.http.agent;

import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.runtime.hitl.ApprovalDecision;
import com.linrun.agent.domain.agent.runtime.hitl.ApprovalGate;
import com.linrun.agent.domain.agent.runtime.hitl.ToolApproval;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent")
public class AgentToolApprovalController {

    private final ApprovalGate approvalGate;

    @GetMapping("/runs/{runId}/approvals/pending")
    public Response<List<Map<String, Object>>> pending(@PathVariable String runId) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        List<Map<String, Object>> approvals = approvalGate.findPending(ownerId, runId).stream()
                .map(this::toResponse)
                .toList();
        return success(approvals);
    }

    @PostMapping("/approvals/{id}/decision")
    public Response<Boolean> decide(@PathVariable long id,
                                    @RequestBody Map<String, Object> body) {
        try {
            String rawDecision = String.valueOf(body.getOrDefault("decision", ""));
            ApprovalDecision decision = ApprovalDecision.valueOf(rawDecision.trim().toUpperCase(Locale.ROOT));
            Object payload = body.containsKey("decisionPayload")
                    ? body.get("decisionPayload")
                    : body.get("modifiedArguments");
            String decisionPayload = payload == null ? null
                    : payload instanceof String value ? value
                    : com.linrun.agent.types.common.JsonUtils.toJson(payload);
            boolean decided = approvalGate.decide(
                    id, OwnerRequestContext.requireOwnerIdAsString(), decision, decisionPayload);
            if (!decided) {
                return failure("审批不存在、已处理、已过期或当前执行已离线");
            }
            return success(true);
        } catch (IllegalArgumentException error) {
            return failure("无效的审批决策");
        }
    }

    private Map<String, Object> toResponse(ToolApproval approval) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", approval.getId());
        result.put("runId", approval.getRunId());
        result.put("toolCallId", approval.getToolCallId());
        result.put("toolName", approval.getToolName());
        result.put("argumentsPreview", approval.getArgumentsPreview());
        result.put("estimatedMicrocredits", approval.getEstimatedMicrocredits());
        result.put("status", approval.getStatus().name());
        result.put("expiresAt", approval.getExpiresAt());
        return result;
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
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(message)
                .build();
    }
}
