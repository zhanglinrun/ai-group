package com.linrun.agent.domain.agent.runtime.printer;

import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;

/**
 * Optional printer capability for emitting projector-built replay envelopes verbatim.
 */
public interface ReplayFrameSink {

    void sendReplayFrame(GptProcessResult frame);
}
