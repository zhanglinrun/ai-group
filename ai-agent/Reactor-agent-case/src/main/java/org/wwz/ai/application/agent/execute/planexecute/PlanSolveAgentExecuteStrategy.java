package org.wwz.ai.application.agent.execute.planexecute;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.execute.IExecuteStrategy;
import org.wwz.ai.application.agent.stream.AgentSessionPrinter;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.memory.ConversationMemoryManager;
import org.wwz.ai.domain.agent.memory.MemoryQuery;
import org.wwz.ai.domain.agent.memory.MemoryTurn;
import org.wwz.ai.domain.agent.service.execute.planexecute.step.factory.DefaultPlanSolveAgentExecuteStrategyFactory;

import java.util.Map;

/**
 * PlanSolve 应用层执行策略。
 */
@Slf4j
@Service("planSolveAgentExecuteStrategy")
public class PlanSolveAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private DefaultPlanSolveAgentExecuteStrategyFactory defaultPlanSolveAgentExecuteStrategyFactory;

    @Resource
    private ReactorConfig reactorConfig;

    @Resource
    private ConversationMemoryManager conversationMemoryManager;

    @Override
    public void execute(AgentRequest request, AgentSessionStream stream) throws Exception {
        String originalQuery = request == null ? null : request.getQuery();
        enrichHistoryDialogue(request);
        applyOutputStyle(request);
        StrategyHandler<AgentRequest, DefaultPlanSolveAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = defaultPlanSolveAgentExecuteStrategyFactory.armoryStrategyHandler();
        DefaultPlanSolveAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                DefaultPlanSolveAgentExecuteStrategyFactory.DynamicContext.builder()
                        .printer(new AgentSessionPrinter(stream, request, request.getAgentType()))
                        .build();
        try {
            String result = executeHandler.apply(request, dynamicContext);
            log.info("PlanSolveAgent execute result: {}", result);
        } catch (Exception e) {
            ExecutionLedgerRunSupport.finishRun(
                    dynamicContext.getAgentContext(),
                    ExecutionLedgerConstants.STATUS_FAILED,
                    null,
                    "PLAN_SOLVE_EXECUTE_ERROR",
                    e == null ? null : e.getMessage()
            );
            throw e;
        }
        persistTurn(request, originalQuery);
    }

    /**
     * 按前端 outputStyle(html/docs/ppt/table) 追加输出格式指令到 query。
     * 与 ReactAgentExecuteStrategy 保持一致，否则深度思考(Plan-Solve)模式选择的输出格式会被忽略、始终产出 HTML。
     */
    private void applyOutputStyle(AgentRequest request) {
        if (request == null || StringUtils.isEmpty(request.getOutputStyle())) {
            return;
        }
        Map<String, String> outputStyleMap = reactorConfig.getOutputStylePrompts();
        String append = outputStyleMap.getOrDefault(request.getOutputStyle(), "");
        if (StringUtils.isNotEmpty(append)) {
            request.setQuery(request.getQuery() + append);
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
