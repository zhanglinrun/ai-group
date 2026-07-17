package com.linrun.agent.domain.agent.runtime.context;

import com.linrun.agent.domain.agent.runtime.dto.Message;

import java.util.List;

/** Context budget and trust-boundary seam used before model calls. */
public interface ContextCompactor {

    ManagedContext prepare(List<Message> sourceMessages, ContextBudget budget);
}
