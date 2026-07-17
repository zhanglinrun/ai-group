package com.linrun.agent.domain.agent.service.execute.agentloop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.service.execute.IExecuteStrategy;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.memory.ConversationMemoryManager;
import com.linrun.agent.domain.agent.memory.MemoryQuery;
import com.linrun.agent.domain.agent.memory.MemoryTurn;
import com.linrun.agent.domain.agent.runtime.AgentRuntime;
import com.linrun.agent.domain.agent.runtime.AgentRuntimeOutcome;
import com.linrun.agent.domain.agent.runtime.printer.Printer;

import java.util.Map;

/**
 * Unified Agent Loop application boundary.
 * Owns conversation-memory and output-style adaptation; the domain harness
 * owns the model/tool loop and terminal protocol.
 */
@Slf4j
@Service("agentLoopExecuteStrategy")
@RequiredArgsConstructor
public class AgentLoopExecuteStrategy implements IExecuteStrategy {

    private final AgentRuntime agentRuntime;
    private final ReactorConfig reactorConfig;
    private final ConversationMemoryManager conversationMemoryManager;

    @Override
    public void execute(AgentRequest request, Printer printer) throws Exception {
        String originalQuery = request == null
                ? null
                : StringUtils.defaultIfBlank(request.getOriginalQuery(), request.getQuery());
        if (request != null) {
            request.setOriginalQuery(originalQuery);
        }
        enrichHistoryDialogue(request);
        applyOutputStyle(request);
        AgentRuntimeOutcome outcome = doExecute(request, printer);
        if (outcome != null && outcome.ownsRunSideEffects()) {
            persistTurn(request, originalQuery);
        }
    }

    private AgentRuntimeOutcome doExecute(AgentRequest request, Printer printer) throws Exception {
        AgentRuntimeOutcome outcome = agentRuntime.runWithOutcome(request, printer);
        String result = outcome == null ? "" : outcome.answer();
        log.info("{} Agent Loop execution finished resultChars={}",
                request == null ? null : request.getRequestId(), result == null ? 0 : result.length());
        return outcome;
    }

    private void applyOutputStyle(AgentRequest request) {
        Map<String, String> outputStyleMap = reactorConfig.getOutputStylePrompts();
        if (StringUtils.isNotEmpty(request.getOutputStyle())) {
            String append = outputStyleMap.computeIfAbsent(request.getOutputStyle(), k -> "");
            request.setQuery(StringUtils.defaultString(request.getOriginalQuery(), request.getQuery()) + append);
        }
    }

    private void enrichHistoryDialogue(AgentRequest request) {
        if (request == null) {
            return;
        }
        request.setHistoryDialogue(conversationMemoryManager == null
                ? ""
                : conversationMemoryManager.assembleHistoryBlock(new MemoryQuery(
                        request.getOwnerId(),
                        request.getSessionId(),
                        request.getRequestId(),
                        request.getQuery())));
    }

    private void persistTurn(AgentRequest request, String originalQuery) {
        if (request == null || conversationMemoryManager == null) {
            return;
        }
        conversationMemoryManager.persistTurnAsync(new MemoryTurn(
                request.getOwnerId(),
                request.getSessionId(),
                request.getRequestId(),
                originalQuery,
                null));
    }
}
