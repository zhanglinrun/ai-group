package org.wwz.ai.domain.agent.runtime.context;

import org.wwz.ai.domain.agent.runtime.dto.Message;

import java.util.List;

/**
 * 经过信任标记和预算治理后，可以直接发送给模型的上下文快照。
 */
public record ManagedContext(List<Message> messages,
                             int originalMessageTokens,
                             int finalMessageTokens,
                             int fixedTokens,
                             int maxInputTokens,
                             boolean compacted) {

    public ManagedContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public int estimatedInputTokens() {
        return Math.addExact(finalMessageTokens, fixedTokens);
    }
}
