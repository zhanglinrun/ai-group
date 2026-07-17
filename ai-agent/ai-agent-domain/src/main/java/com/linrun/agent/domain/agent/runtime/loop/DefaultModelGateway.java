package com.linrun.agent.domain.agent.runtime.loop;

import com.linrun.agent.domain.agent.runtime.harness.AgentFutureWaiter;
import com.linrun.agent.domain.agent.runtime.llm.LLM;

import java.util.Objects;

/** Default gateway backed by the existing LLM transport and accounting layer. */
public final class DefaultModelGateway implements ModelGateway {

    private final LLM llm;

    public DefaultModelGateway(LLM llm) {
        this.llm = Objects.requireNonNull(llm, "llm must not be null");
    }

    @Override
    public ModelTurnResponse complete(ModelTurnRequest request) throws Exception {
        LLM.ToolCallResponse response = AgentFutureWaiter.await(
                llm.askTool(
                        request.context(),
                        request.messages(),
                        request.systemMessage(),
                        request.tools(),
                        request.toolChoice(),
                        request.temperature(),
                        request.stream(),
                        request.pushToClient(),
                        request.timeoutSeconds()
                ),
                request.context(),
                request.callLimit()
        );
        return ModelTurnResponse.fromProvider(
                response.getContent(), response.getToolCalls(), response.getFinishReason());
    }

    @Override
    public String functionCallType() {
        return llm.getFunctionCallType();
    }
}
