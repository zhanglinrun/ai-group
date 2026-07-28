package com.linrun.agent.domain.agent.runtime.tool.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.adapter.port.PlatformContextPort;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.PlatformContextToolOutput;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only bridge to account, pricing, group-buy, and order context.
 * Identity is never model-controlled: ownerId is read exclusively from AgentContext.
 */
@Slf4j
public class PlatformContextTool implements BaseTool {

    public static final String NAME = PlatformContextToolOutput.TOOL_NAME;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> FORBIDDEN_IDENTITY_FIELDS = Set.of(
            "userid", "ownerid", "x-user-id", "xuserid");

    @Setter
    private AgentContext agentContext;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "只读查询当前登录用户的平台业务上下文。operation 仅支持 account_summary（额度账户）、"
                + "pricing（套餐与价格）、group_buy（拼团，可选 activityId）、orders（订单）。"
                + "身份只取服务端 AgentContext；本工具不会创建订单、支付、退款或修改额度，购买相关请求只返回页面 CTA。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "string");
        operation.put("description", "只读操作枚举。");
        operation.put("enum", List.of("account_summary", "pricing", "group_buy", "orders"));

        Map<String, Object> activityId = new LinkedHashMap<>();
        activityId.put("type", "integer");
        activityId.put("minimum", 1);
        activityId.put("description", "仅 group_buy 可选；指定时查询该活动详情，省略时查询拼团大厅摘要。");

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "operation", operation,
                "activityId", activityId));
        schema.put("required", List.of("operation"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object execute(Object input) {
        try {
            Map<?, ?> params = requireInputMap(input);
            if (containsIdentityField(params)) {
                return failure(null, "身份参数不允许由模型提供；用户身份只能来自 AgentContext。");
            }
            Operation operation = Operation.resolve(params.get("operation"));
            Long activityId = optionalPositiveLong(params.get("activityId"));
            if (activityId != null && operation != Operation.GROUP_BUY) {
                return failure(operation.value, "activityId 仅允许用于 group_buy 操作。");
            }

            Long ownerId = requireOwnerId();
            PlatformContextPort port = requirePort();
            PlatformContextPort.ContextResult<?> result = switch (operation) {
                case ACCOUNT_SUMMARY -> port.accountSummary(ownerId);
                case PRICING -> port.pricing(ownerId);
                case GROUP_BUY -> port.groupBuy(ownerId, activityId);
                case ORDERS -> port.orders(ownerId);
            };
            return success(operation, activityId, result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return failure(resolveOperation(input), e.getMessage());
        } catch (Exception e) {
            log.error("platform_context query failed requestId={} errorType={}",
                    agentContext == null ? null : agentContext.getRequestId(),
                    e.getClass().getSimpleName(), e);
            return failure(resolveOperation(input), "平台业务上下文暂时不可用，请稍后重试。");
        }
    }

    @Override
    public boolean isConcurrencySafe(Object input) {
        return true;
    }

    private ToolResultPayload success(Operation operation,
                                      Long activityId,
                                      PlatformContextPort.ContextResult<?> result) {
        PlatformContextPort.BffMeta meta = result.meta();
        boolean degraded = meta.degraded();
        boolean empty = isEmpty(operation, result.data());
        String status = degraded ? "DEGRADED" : "COMPLETE";
        String message = degraded
                ? "BFF 返回了降级数据；空列表表示下游不可用或结果未知，不能解释为确认无数据。"
                : empty ? "BFF 已确认当前结果为空。" : "BFF 已返回完整只读数据。";
        PlatformContextToolOutput output = PlatformContextToolOutput.builder()
                .operation(operation.value)
                .status(status)
                .complete(!degraded)
                .degraded(degraded)
                .authoritativeEmpty(empty && !degraded)
                .degradationErrors(meta.errors())
                .data(result.data())
                .cta(cta(operation, activityId))
                .message(message)
                .build();
        String json = toJson(output);
        String observation = "platform_context status=" + status + ". " + message + "\n" + json;
        if (degraded) {
            // A partial BFF fallback must not become successful completion evidence.
            // The typed output still carries partial data and CTA so the model can
            // explain the outage or retry this read on a later turn.
            return ToolResultPayload.failure(json, observation, output, message);
        }
        return ToolResultPayload.structured(json, observation, output);
    }

    private ToolResultPayload failure(String operation, String message) {
        String safeMessage = StringUtils.defaultIfBlank(message, "platform_context 查询失败。");
        PlatformContextToolOutput output = PlatformContextToolOutput.builder()
                .operation(operation)
                .status("FAILED")
                .complete(false)
                .degraded(false)
                .authoritativeEmpty(false)
                .degradationErrors(List.of())
                .message(safeMessage)
                .build();
        String json = toJson(output);
        return ToolResultPayload.failure(json, safeMessage, output, safeMessage);
    }

    private String toJson(PlatformContextToolOutput output) {
        try {
            // Platform BFF DTOs are Java records. Jackson serializes record accessors,
            // while the former bean conversion path silently emitted data={}.
            return OBJECT_MAPPER.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("platform_context 结构化结果序列化失败。", e);
        }
    }

    private PlatformContextToolOutput.NavigationCta cta(Operation operation, Long activityId) {
        return switch (operation) {
            case ACCOUNT_SUMMARY -> new PlatformContextToolOutput.NavigationCta("查看账户额度", "/account");
            case PRICING -> new PlatformContextToolOutput.NavigationCta("查看套餐并购买", "/pricing");
            case GROUP_BUY -> new PlatformContextToolOutput.NavigationCta(
                    "查看拼团并购买", activityId == null ? "/group-buy" : "/group-buy/" + activityId);
            case ORDERS -> new PlatformContextToolOutput.NavigationCta("查看订单", "/orders");
        };
    }

    private boolean isEmpty(Operation operation, Object data) {
        return switch (operation) {
            case ACCOUNT_SUMMARY -> data instanceof PlatformContextPort.AccountSummary summary
                    && summary.quotaLedger().isEmpty()
                    && summary.pendingGroupOrders().isEmpty()
                    && summary.availableQuota() == null;
            case PRICING -> data instanceof PlatformContextPort.Pricing pricing
                    && pricing.skus().isEmpty()
                    && (pricing.groupBuy() == null || pricing.groupBuy().unavailable());
            case GROUP_BUY -> data instanceof PlatformContextPort.GroupBuy groupBuy
                    && groupBuy.skus().isEmpty()
                    && (groupBuy.groupBuy() == null || groupBuy.groupBuy().unavailable());
            case ORDERS -> data instanceof PlatformContextPort.Orders orders && orders.items().isEmpty();
        };
    }

    private Map<?, ?> requireInputMap(Object input) {
        if (!(input instanceof Map<?, ?> params)) {
            throw new IllegalArgumentException("platform_context 输入必须是对象。");
        }
        return params;
    }

    private boolean containsIdentityField(Map<?, ?> params) {
        for (Object rawKey : params.keySet()) {
            if (rawKey == null) {
                continue;
            }
            String normalized = String.valueOf(rawKey)
                    .replace("_", "")
                    .toLowerCase(Locale.ROOT);
            if (FORBIDDEN_IDENTITY_FIELDS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private Long requireOwnerId() {
        Long ownerId = agentContext == null ? null : agentContext.getOwnerId();
        if (ownerId == null || ownerId <= 0L) {
            throw new IllegalStateException("当前 AgentContext 缺少可信 ownerId，不能查询平台业务数据。");
        }
        return ownerId;
    }

    private PlatformContextPort requirePort() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("platform_context 缺少运行时依赖。");
        }
        return agentContext.getRuntimeDependencies().requirePlatformContextPort();
    }

    private Long optionalPositiveLong(Object value) {
        if (value == null) {
            return null;
        }
        long parsed;
        if (value instanceof Number number) {
            parsed = number.longValue();
        } else {
            try {
                parsed = Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("activityId 必须是正整数。");
            }
        }
        if (parsed <= 0L) {
            throw new IllegalArgumentException("activityId 必须是正整数。");
        }
        return parsed;
    }

    private String resolveOperation(Object input) {
        if (!(input instanceof Map<?, ?> params) || params.get("operation") == null) {
            return null;
        }
        return String.valueOf(params.get("operation"));
    }

    private enum Operation {
        ACCOUNT_SUMMARY("account_summary"),
        PRICING("pricing"),
        GROUP_BUY("group_buy"),
        ORDERS("orders");

        private final String value;

        Operation(String value) {
            this.value = value;
        }

        private static Operation resolve(Object rawValue) {
            String value = rawValue == null ? null : String.valueOf(rawValue).trim().toLowerCase(Locale.ROOT);
            for (Operation operation : values()) {
                if (operation.value.equals(value)) {
                    return operation;
                }
            }
            throw new IllegalArgumentException(
                    "operation 必须是 account_summary、pricing、group_buy 或 orders。");
        }
    }
}
