package com.linrun.agent.domain.agent.reactor.service;

import com.linrun.agent.domain.agent.reactor.model.imagegeneration.WorkspaceImageGenerationCommand;
import com.linrun.agent.domain.agent.reactor.model.imagegeneration.WorkspaceImageGenerationHistoryPage;
import com.linrun.agent.domain.agent.reactor.model.imagegeneration.WorkspaceImageGenerationResult;

/**
 * 生图工作台服务。
 */
public interface IWorkspaceImageGenerationService {

    /**
     * 发起一次生图工作台请求。
     */
    WorkspaceImageGenerationResult generate(WorkspaceImageGenerationCommand command);

    /**
     * 分页查询工作台生图历史。
     */
    WorkspaceImageGenerationHistoryPage queryHistory(Long ownerId, int pageNo, int pageSize);

    boolean deleteHistory(Long ownerId, String requestId);
}
