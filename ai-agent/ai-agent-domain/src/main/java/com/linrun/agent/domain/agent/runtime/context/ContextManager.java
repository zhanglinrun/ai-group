package com.linrun.agent.domain.agent.runtime.context;

import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.enums.RoleType;
import com.linrun.agent.domain.agent.runtime.llm.TokenCounter;

import java.util.ArrayList;
import java.util.List;

/**
 * 所有主 Agent LLM 调用共享的上下文治理器。
 *
 * <p>治理顺序：标记外部数据 -> 压缩大型 observation/召回块 -> 扣除工具 schema ->
 * 保留系统指令、当前用户输入和最近的完整工具调用单元。</p>
 */
public final class ContextManager implements ContextCompactor {

    private static final int MIN_REQUIRED_MESSAGE_TOKENS = 24;
    private static final int MESSAGE_LIST_FORMAT_TOKENS = 2;

    private final TokenCounter tokenCounter;

    public ContextManager(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter == null ? new TokenCounter() : tokenCounter;
    }

    @Override
    public ManagedContext prepare(List<Message> sourceMessages, ContextBudget budget) {
        ContextBudget effectiveBudget = budget == null
                ? ContextBudget.forModel(0, 0)
                : budget;
        List<Message> normalized = normalize(sourceMessages, effectiveBudget.maxUntrustedContentTokens());
        int originalTokens = tokenCounter.countMessages(normalized);

        if (!effectiveBudget.isBounded()) {
            return new ManagedContext(normalized, originalTokens, originalTokens,
                    effectiveBudget.fixedTokens(), effectiveBudget.maxInputTokens(),
                    !sameMessages(sourceMessages, normalized));
        }

        int messageBudget = effectiveBudget.messageTokenBudget();
        if (messageBudget < MIN_REQUIRED_MESSAGE_TOKENS) {
            throw new ContextBudgetExceededException(
                    "模型输入预算不足：工具 schema 与协议开销已占用 "
                            + (effectiveBudget.fixedTokens() + effectiveBudget.safetyMarginTokens())
                            + " tokens，maxInputTokens=" + effectiveBudget.maxInputTokens());
        }

        List<Message> prepared = originalTokens <= messageBudget
                ? normalized
                : fitToBudget(normalized, messageBudget);
        int finalTokens = tokenCounter.countMessages(prepared);
        if (finalTokens > messageBudget) {
            throw new ContextBudgetExceededException(
                    "必要系统指令与当前输入超过消息预算：required=" + finalTokens
                            + ", budget=" + messageBudget);
        }
        return new ManagedContext(prepared, originalTokens, finalTokens,
                effectiveBudget.fixedTokens(), effectiveBudget.maxInputTokens(),
                originalTokens != finalTokens || !sameMessages(sourceMessages, normalized));
    }

    private List<Message> normalize(List<Message> messages, int maxUntrustedTokens) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Message> normalized = new ArrayList<>(messages.size());
        for (Message source : messages) {
            if (source == null || source.getRole() == null) {
                continue;
            }
            Message copy = copyOf(source);
            String content = StringUtils.defaultString(copy.getContent());
            if (copy.getRole() == RoleType.TOOL) {
                String compacted = ContextTrustBoundary.compactEvidence(content, maxUntrustedTokens, tokenCounter);
                copy.setContent(ContextTrustBoundary.wrap(
                        "tool:" + StringUtils.defaultString(copy.getToolCallId()), compacted));
            } else if (ContextTrustBoundary.containsBoundary(content)) {
                copy.setContent(ContextTrustBoundary.compactEmbeddedSections(
                        content, maxUntrustedTokens, tokenCounter));
            }
            normalized.add(copy);
        }
        return normalized;
    }

    private List<Message> fitToBudget(List<Message> messages, int messageBudget) {
        List<Message> systems = messages.stream()
                .filter(message -> message.getRole() == RoleType.SYSTEM)
                .map(ContextManager::copyOf)
                .toList();
        List<Message> conversation = messages.stream()
                .filter(message -> message.getRole() != RoleType.SYSTEM)
                .map(ContextManager::copyOf)
                .toList();

        List<MessageUnit> units = toUserTurns(conversation);
        int latestUserUnit = findLatestUserUnit(units);
        int requiredConversationTokens = latestUserUnit < 0
                ? 0
                : tokenCounter.countMessages(units.get(latestUserUnit).messages());
        int minimumSystemTokens = systems.isEmpty() ? 0 : MIN_REQUIRED_MESSAGE_TOKENS;
        if (requiredConversationTokens + minimumSystemTokens > messageBudget) {
            throw new ContextBudgetExceededException(
                    "最新用户轮次与必要系统指令超过消息预算：required="
                            + (requiredConversationTokens + minimumSystemTokens)
                            + ", budget=" + messageBudget);
        }
        int systemLimit = Math.max(0, messageBudget - requiredConversationTokens);
        List<Message> fittedSystems = fitMessagesInOrder(systems, systemLimit, true);
        int remaining = Math.max(0, messageBudget - tokenCounter.countMessages(fittedSystems));
        int selectedStart = latestUserUnit < 0 ? units.size() : latestUserUnit;
        if (latestUserUnit >= 0) {
            remaining -= requiredConversationTokens;
            for (int i = latestUserUnit - 1; i >= 0; i--) {
                int unitTokens = tokenCounter.countMessages(units.get(i).messages());
                if (unitTokens > remaining) break;
                selectedStart = i;
                remaining -= unitTokens;
            }
        }

        List<Message> summary = selectedStart > 0
                ? buildHistorySummary(units.subList(0, selectedStart), remaining)
                : List.of();
        List<Message> result = new ArrayList<>(fittedSystems);
        result.addAll(summary);
        for (int i = selectedStart; i < units.size(); i++) {
            result.addAll(units.get(i).messages());
        }
        return result;
    }

    private List<Message> buildHistorySummary(List<MessageUnit> omitted, int budget) {
        if (omitted.isEmpty() || budget < MIN_REQUIRED_MESSAGE_TOKENS * 2) return List.of();
        StringBuilder raw = new StringBuilder("历史摘要：\n");
        omitted.forEach(unit -> unit.messages().forEach(message -> raw
                .append(message.getRole().name().toLowerCase())
                .append(": ")
                .append(StringUtils.defaultString(message.getContent()))
                .append('\n')));
        Message acknowledgement = Message.assistantMessage("已了解上述历史上下文。", null);
        Message emptySummary = Message.userMessage("", null);
        int contentBudget = budget
                - tokenCounter.countMessageTokens(acknowledgement)
                - tokenCounter.countMessageTokens(emptySummary)
                - MESSAGE_LIST_FORMAT_TOKENS;
        if (contentBudget <= 0) return List.of();
        Message summary = Message.userMessage(
                tokenCounter.truncateTextToTokens(raw.toString(), contentBudget), null);
        List<Message> pair = List.of(summary, acknowledgement);
        return tokenCounter.countMessages(pair) <= budget ? pair : List.of();
    }

    private List<Message> fitMessagesInOrder(List<Message> messages, int budget, boolean required) {
        if (messages.isEmpty() || budget <= 0) {
            return List.of();
        }
        List<Message> result = new ArrayList<>();
        int remaining = Math.max(0, budget - MESSAGE_LIST_FORMAT_TOKENS);
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            int tokens = tokenCounter.countMessageTokens(message);
            if (tokens <= remaining) {
                result.add(message);
                remaining -= tokens;
                continue;
            }
            if (required && remaining >= MIN_REQUIRED_MESSAGE_TOKENS) {
                result.add(fitSingleRequiredMessage(message, remaining));
            }
            break;
        }
        return result;
    }

    private Message fitSingleRequiredMessage(Message message, int budget) {
        Message copy = copyOf(message);
        int structuralTokens = tokenCounter.countMessageTokens(copy) - tokenCounter.countText(copy.getContent());
        int contentBudget = Math.max(1, budget - Math.max(0, structuralTokens));
        String content = StringUtils.defaultString(copy.getContent());
        copy.setContent(copy.getRole() == RoleType.SYSTEM
                ? truncateSystemPromptKeepingEnds(content, contentBudget)
                : tokenCounter.truncateTextToTokens(content, contentBudget));
        return copy;
    }

    private String truncateSystemPromptKeepingEnds(String content, int maxTokens) {
        if (tokenCounter.countText(content) <= maxTokens) {
            return content;
        }
        String marker = "\n...[middle truncated]...\n";
        int markerTokens = tokenCounter.countText(marker);
        if (maxTokens <= markerTokens + 2) {
            return tokenCounter.truncateTextToTokens(content, maxTokens);
        }

        int totalCodePoints = content.codePointCount(0, content.length());
        int tailBudget = Math.max(1, (maxTokens - markerTokens) / 3);
        String suffix = longestSuffixWithinTokens(content, tailBudget);
        int suffixCodePoints = suffix.codePointCount(0, suffix.length());
        int high = Math.max(0, totalCodePoints - suffixCodePoints);
        int low = 0;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int endIndex = content.offsetByCodePoints(0, middle);
            String candidate = content.substring(0, endIndex).stripTrailing()
                    + marker
                    + suffix.stripLeading();
            if (tokenCounter.countText(candidate) <= maxTokens) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        int prefixEnd = content.offsetByCodePoints(0, low);
        String result = content.substring(0, prefixEnd).stripTrailing()
                + marker
                + suffix.stripLeading();
        return tokenCounter.countText(result) <= maxTokens
                ? result
                : tokenCounter.truncateTextToTokens(content, maxTokens);
    }

    private String longestSuffixWithinTokens(String content, int maxTokens) {
        int totalCodePoints = content.codePointCount(0, content.length());
        int low = 0;
        int high = totalCodePoints;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int startIndex = content.offsetByCodePoints(0, totalCodePoints - middle);
            if (tokenCounter.countText(content.substring(startIndex)) <= maxTokens) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        int startIndex = content.offsetByCodePoints(0, totalCodePoints - low);
        return content.substring(startIndex);
    }

    private List<MessageUnit> toUserTurns(List<Message> messages) {
        List<MessageUnit> units = new ArrayList<>();
        List<Message> current = new ArrayList<>();
        int start = 0;
        boolean containsUser = false;
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message.getRole() == RoleType.USER && !current.isEmpty()) {
                units.add(new MessageUnit(start, List.copyOf(current), containsUser));
                current.clear();
                start = index;
                containsUser = false;
            }
            current.add(message);
            containsUser |= message.getRole() == RoleType.USER;
        }
        if (!current.isEmpty()) units.add(new MessageUnit(start, List.copyOf(current), containsUser));
        return units;
    }

    private int findLatestUserUnit(List<MessageUnit> units) {
        for (int i = units.size() - 1; i >= 0; i--) {
            if (units.get(i).containsUser()) return i;
        }
        return -1;
    }

    private static Message copyOf(Message source) {
        return Message.builder()
                .role(source.getRole())
                .content(source.getContent())
                .base64Image(source.getBase64Image())
                .toolCallId(source.getToolCallId())
                .toolCalls(source.getToolCalls() == null ? null : List.copyOf(source.getToolCalls()))
                .build();
    }

    private boolean sameMessages(List<Message> left, List<Message> right) {
        List<Message> safeLeft = left == null ? List.of() : left.stream().filter(message -> message != null).toList();
        return safeLeft.equals(right);
    }

    private record MessageUnit(int startIndex, List<Message> messages, boolean containsUser) {
    }
}
