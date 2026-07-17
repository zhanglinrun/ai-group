package com.linrun.agent.domain.agent.runtime.context;

import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.enums.RoleType;
import com.linrun.agent.domain.agent.runtime.llm.TokenCounter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

        int latestUserIndex = findLatestUserIndex(conversation);
        int minimumConversationBudget = latestUserIndex >= 0 ? Math.min(256, Math.max(64, messageBudget / 4)) : 0;
        int systemLimit = Math.max(0, messageBudget - minimumConversationBudget);
        List<Message> fittedSystems = fitMessagesInOrder(systems, systemLimit, true);
        int remaining = Math.max(0, messageBudget - tokenCounter.countMessages(fittedSystems));

        List<MessageUnit> units = toAtomicUnits(conversation);
        int latestUserUnit = findUnitContainingIndex(units, latestUserIndex);
        Set<Integer> selected = new HashSet<>();

        if (latestUserUnit >= 0) {
            MessageUnit userUnit = units.get(latestUserUnit);
            Message fittedUser = fitSingleRequiredMessage(userUnit.messages().get(0), remaining);
            units.set(latestUserUnit, new MessageUnit(userUnit.startIndex(), List.of(fittedUser)));
            selected.add(latestUserUnit);
            remaining -= tokenCounter.countMessageTokens(fittedUser);
        }

        for (int i = units.size() - 1; i >= 0; i--) {
            if (i == latestUserUnit) {
                continue;
            }
            int unitTokens = tokenCounter.countMessages(units.get(i).messages());
            if (unitTokens <= remaining) {
                selected.add(i);
                remaining -= unitTokens;
            }
        }

        List<Message> result = new ArrayList<>(fittedSystems);
        selected.stream().sorted(Comparator.naturalOrder())
                .forEach(index -> result.addAll(units.get(index).messages()));
        return result;
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

    private List<MessageUnit> toAtomicUnits(List<Message> messages) {
        List<MessageUnit> units = new ArrayList<>();
        for (int index = 0; index < messages.size();) {
            int start = index;
            List<Message> unit = new ArrayList<>();
            Message current = messages.get(index++);
            unit.add(current);
            if (current.getRole() == RoleType.ASSISTANT
                    && current.getToolCalls() != null
                    && !current.getToolCalls().isEmpty()) {
                while (index < messages.size() && messages.get(index).getRole() == RoleType.TOOL) {
                    unit.add(messages.get(index++));
                }
            }
            units.add(new MessageUnit(start, unit));
        }
        return units;
    }

    private int findLatestUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == RoleType.USER) {
                return i;
            }
        }
        return -1;
    }

    private int findUnitContainingIndex(List<MessageUnit> units, int messageIndex) {
        if (messageIndex < 0) {
            return -1;
        }
        for (int i = 0; i < units.size(); i++) {
            MessageUnit unit = units.get(i);
            int end = unit.startIndex() + unit.messages().size();
            if (messageIndex >= unit.startIndex() && messageIndex < end) {
                return i;
            }
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

    private record MessageUnit(int startIndex, List<Message> messages) {
    }
}
