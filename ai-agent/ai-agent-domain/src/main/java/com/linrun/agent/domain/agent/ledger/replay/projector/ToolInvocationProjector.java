package com.linrun.agent.domain.agent.ledger.replay.projector;

import com.linrun.agent.domain.agent.ledger.model.ArtifactView;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationView;
import com.linrun.agent.domain.agent.reactor.model.multi.EventResult;
import com.linrun.agent.domain.agent.ledger.model.replay.ProjectedReplayEvent;

import java.util.List;

/**
 * 单工具账本投影器。
 */
public interface ToolInvocationProjector {

    /**
     * 是否支持该工具。
     */
    boolean supports(String toolName);

    /**
     * 把工具调用事实投影成前端可消费 eventData。
     */
    List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                       List<ArtifactView> artifacts,
                                       EventResult state);
}
