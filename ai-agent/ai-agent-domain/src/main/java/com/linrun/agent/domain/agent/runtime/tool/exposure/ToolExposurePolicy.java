package com.linrun.agent.domain.agent.runtime.tool.exposure;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.ExecuteExtraTool;
import com.linrun.agent.domain.agent.runtime.tool.common.PlatformContextTool;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude Code 风格的两级工具暴露策略：内置工具直接暴露，deferred MCP 工具只通过稳定代理执行。
 * 完整 ToolCollection 仍是执行目录；本类只创建单轮模型可见且可执行的浅视图。
 * 一旦进入 filtered/auto deferred 模式，发现结果不会再改变后续轮次的 provider tool schema 数组。
 */
@Slf4j
public final class ToolExposurePolicy {

    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9][a-z0-9_-]{1,}");
    private static final Pattern CJK_SEQUENCE = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final List<String> PLATFORM_CONTEXT_STRONG_TERMS = List.of(
            "额度", "套餐", "拼团", "团购", "会员", "权益",
            "quota", "group buy", "group-buy", "membership plan");
    private static final List<String> PLATFORM_CONTEXT_AMBIGUOUS_TERMS = List.of(
            "余额", "账户", "账号", "定价", "价格", "充值", "订单", "购买", "下单", "支付",
            "balance", "account", "pricing", "price", "order", "purchase", "buy", "payment");
    private static final List<String> PLATFORM_CONTEXT_QUALIFIERS = List.of(
            "我的", "当前账户", "当前账号", "本账户", "本平台", "这个平台", "平台账户", "平台套餐",
            "账户额度", "可用额度", "剩余额度",
            "my account", "current account", "this platform", "platform account", "my order", "my orders",
            "my balance", "my subscription");

    private ToolExposurePolicy() {
    }

    public static ToolCollection selectForTurn(ToolCollection catalog,
                                               AgentContext context,
                                               ReactorConfig config) {
        if (catalog == null) {
            return new ToolCollection();
        }
        String mode = normalizedMode(config == null ? null : config.getToolExposureMode());
        int inlineLimit = positive(config == null ? null : config.getToolExposureMaxInlineMcpTools(), 8);
        int selectedLimit = positive(config == null ? null : config.getToolExposureMaxSelectedMcpTools(), 6);
        int mcpCount = catalog.getMcpToolMap().size();
        boolean stableDeferredProxy = usesStableDeferredProxy(mode, mcpCount, inlineLimit);
        boolean shouldObserveSelection = stableDeferredProxy
                || ("shadow".equals(mode) && mcpCount > inlineLimit);

        ToolCollection selected = catalog;
        int candidateMcpCount = mcpCount;
        if (stableDeferredProxy) {
            Set<String> names = new LinkedHashSet<>(catalog.getToolMap().keySet());
            selected = catalog.selectedView(names);
            candidateMcpCount = 0;
            log.info("{} tool exposure mode={} catalog={} mcp={} selectedMcp={} exposed={} schemaChars={}",
                    context == null ? null : context.getRequestId(), mode, catalog.toolCount(), mcpCount,
                    0, selected.toolCount(), selected.estimateSchemaChars());
        } else if (shouldObserveSelection) {
            String searchText = StringUtils.defaultString(context == null ? null : context.getQuery())
                    + "\n" + StringUtils.defaultString(context == null ? null : context.getTask());
            candidateMcpCount = searchMcpTools(catalog, searchText, selectedLimit).size();
            log.info("{} tool exposure shadow mode={} catalog={} mcp={} candidateMcp={} exposed={} schemaChars={}",
                    context == null ? null : context.getRequestId(), mode, catalog.toolCount(), mcpCount,
                    candidateMcpCount, selected.toolCount(), selected.estimateSchemaChars());
        }

        ToolInvocationContract invocationContract = context == null
                ? null
                : context.getToolInvocationContract();
        selected = applyBuiltInSemanticSelection(catalog, selected, context, invocationContract);
        selected = applyInvocationContract(catalog, selected, invocationContract, stableDeferredProxy);

        if (context != null && context.getAgentRunState() != null) {
            int contractMcpCount = selected.getMcpToolMap().size();
            int effectiveMcpCount = invocationContract != null && invocationContract.constrained()
                    ? contractMcpCount
                    : candidateMcpCount;
            int deferred = stableDeferredProxy || invocationContract != null && invocationContract.constrained()
                    ? Math.max(0, mcpCount - effectiveMcpCount)
                    : 0;
            context.getAgentRunState().recordToolExposure(
                    catalog.toolCount(), selected.toolCount(), deferred, selected.estimateSchemaChars());
        }
        return selected;
    }

    /**
     * Keep the owner-bound business tool out of unrelated turns. Explicit user
     * tool contracts still win and can expose it by canonical name.
     */
    private static ToolCollection applyBuiltInSemanticSelection(ToolCollection catalog,
                                                                ToolCollection selected,
                                                                AgentContext context,
                                                                ToolInvocationContract contract) {
        if (selected == null || !selected.getToolMap().containsKey(PlatformContextTool.NAME)) {
            return selected;
        }
        boolean explicitlyRequired = contract != null
                && contract.requiredToolNames().contains(PlatformContextTool.NAME);
        String searchText = StringUtils.defaultString(context == null ? null : context.getQuery())
                + "\n" + StringUtils.defaultString(context == null ? null : context.getTask());
        if (explicitlyRequired || hasPlatformContextSemantics(searchText)) {
            return selected;
        }
        Set<String> names = new LinkedHashSet<>(selected.getToolMap().keySet());
        names.addAll(selected.getMcpToolMap().keySet());
        names.remove(PlatformContextTool.NAME);
        return catalog.selectedView(names);
    }

    private static boolean hasPlatformContextSemantics(String value) {
        String normalized = StringUtils.defaultString(value).toLowerCase(Locale.ROOT);
        if (containsAnyTerm(normalized, PLATFORM_CONTEXT_STRONG_TERMS)) {
            return true;
        }
        return containsAnyTerm(normalized, PLATFORM_CONTEXT_AMBIGUOUS_TERMS)
                && containsAnyTerm(normalized, PLATFORM_CONTEXT_QUALIFIERS);
    }

    private static boolean containsAnyTerm(String normalized, List<String> terms) {
        for (String term : terms) {
            if (containsTerm(normalized, term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTerm(String normalized, String term) {
        if (term.codePoints().anyMatch(
                codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
            return normalized.contains(term);
        }
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(term) + "(?![a-z0-9])")
                .matcher(normalized)
                .find();
    }

    private static ToolCollection applyInvocationContract(ToolCollection catalog,
                                                           ToolCollection selected,
                                                           ToolInvocationContract contract,
                                                           boolean stableDeferredProxy) {
        if (contract == null || !contract.constrained()) {
            return selected;
        }
        Set<String> names = new LinkedHashSet<>();
        if (contract.exclusive()) {
            for (String allowedToolName : contract.allowedToolNames()) {
                if (stableDeferredProxy && catalog.getMcpToolMap().containsKey(allowedToolName)) {
                    if (catalog.getToolMap().containsKey(ExecuteExtraTool.NAME)) {
                        names.add(ExecuteExtraTool.NAME);
                    }
                } else {
                    names.add(allowedToolName);
                }
            }
        } else if (selected != null) {
            names.addAll(selected.getToolMap().keySet());
            names.addAll(selected.getMcpToolMap().keySet());
            names.removeAll(contract.forbiddenToolNames());
        }
        if (catalog.getToolMap().containsKey(TodoWriteTool.NAME)) {
            names.add(TodoWriteTool.NAME);
        }
        return catalog.selectedView(names);
    }

    public static boolean shouldAttachSearchTool(ReactorConfig config, int mcpToolCount) {
        String mode = normalizedMode(config == null ? null : config.getToolExposureMode());
        int inlineLimit = positive(config == null ? null : config.getToolExposureMaxInlineMcpTools(), 8);
        return usesStableDeferredProxy(mode, mcpToolCount, inlineLimit);
    }

    private static boolean usesStableDeferredProxy(String mode, int mcpToolCount, int inlineLimit) {
        return mcpToolCount > 0 && ("filtered".equals(mode)
                || "auto".equals(mode) && mcpToolCount > inlineLimit);
    }

    public static List<McpToolInfo> searchMcpTools(ToolCollection catalog, String query, int limit) {
        if (catalog == null || catalog.getMcpToolMap().isEmpty() || StringUtils.isBlank(query) || limit <= 0) {
            return List.of();
        }
        Set<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<ScoredTool> scored = new ArrayList<>();
        for (McpToolInfo tool : catalog.getMcpToolMap().values()) {
            int score = score(tool, query, terms);
            if (score > 0) {
                scored.add(new ScoredTool(tool, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(ScoredTool::score).reversed()
                        .thenComparing(item -> item.tool().resolveExposedName()))
                .limit(limit)
                .map(ScoredTool::tool)
                .toList();
    }

    private static int score(McpToolInfo tool, String rawQuery, Set<String> terms) {
        String query = rawQuery.toLowerCase(Locale.ROOT);
        String exposedName = StringUtils.defaultString(tool.resolveExposedName()).toLowerCase(Locale.ROOT);
        String remoteName = StringUtils.defaultString(tool.getName()).toLowerCase(Locale.ROOT);
        String description = StringUtils.defaultString(tool.getDesc()).toLowerCase(Locale.ROOT);
        String parameters = StringUtils.defaultString(tool.getParameters()).toLowerCase(Locale.ROOT);
        int score = 0;
        if (query.contains(exposedName) || query.contains(remoteName)) {
            score += 100;
        }
        for (String term : terms) {
            if (exposedName.contains(term) || remoteName.contains(term)) {
                score += 12;
            }
            if (description.contains(term)) {
                score += 4;
            }
            if (parameters.contains(term)) {
                score += 1;
            }
        }
        return score;
    }

    private static Set<String> tokenize(String value) {
        String normalized = StringUtils.defaultString(value).toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        Matcher latin = LATIN_TOKEN.matcher(normalized);
        while (latin.find() && terms.size() < 80) {
            String token = latin.group();
            terms.add(token);
            for (String part : token.split("[_-]")) {
                if (part.length() >= 2) {
                    terms.add(part);
                }
            }
        }
        Matcher cjk = CJK_SEQUENCE.matcher(normalized);
        while (cjk.find() && terms.size() < 80) {
            String sequence = cjk.group();
            if (sequence.length() <= 6) {
                terms.add(sequence);
            }
            for (int i = 0; i + 1 < sequence.length() && terms.size() < 80; i++) {
                terms.add(sequence.substring(i, i + 2));
            }
        }
        return terms;
    }

    private static String normalizedMode(String mode) {
        String normalized = StringUtils.defaultIfBlank(mode, "auto").trim().toLowerCase(Locale.ROOT);
        return Set.of("all", "shadow", "auto", "filtered").contains(normalized) ? normalized : "auto";
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private record ScoredTool(McpToolInfo tool, int score) {
    }
}
