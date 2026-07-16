package org.wwz.ai.application.agent.execute.planexecute;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.execute.IExecuteStrategy;
import org.wwz.ai.application.agent.stream.AgentSessionPrinter;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointCoordinator;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointState;
import org.wwz.ai.domain.agent.checkpoint.PlanExecutionCheckpoint;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.memory.ConversationMemoryManager;
import org.wwz.ai.domain.agent.memory.MemoryQuery;
import org.wwz.ai.domain.agent.memory.MemoryTurn;
import org.wwz.ai.domain.agent.service.execute.planexecute.step.factory.DefaultPlanSolveAgentExecuteStrategyFactory;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/**
 * PlanSolve 应用层执行策略。
 */
@Slf4j
@Service("planSolveAgentExecuteStrategy")
public class PlanSolveAgentExecuteStrategy implements IExecuteStrategy {

    private static final Pattern EXPLICIT_NO_TOOLS = Pattern.compile(
            "(?:不要|请勿|禁止|无需|不需要)\\s*(?:调用|使用)\\s*(?:任何|任意|外部)?\\s*工具"
                    + "|(?i:\\b(?:no\\s+(?:external\\s+)?tools?"
                    + "|(?:do\\s+not|don't|dont|never)\\s+(?:call|use|invoke)\\s+"
                    + "(?:any\\s+|external\\s+)?tools?"
                    + "|without\\s+(?:calling|using|invoking)\\s+(?:any\\s+|external\\s+)?tools?)\\b)"
    );

    @Resource
    private DefaultPlanSolveAgentExecuteStrategyFactory defaultPlanSolveAgentExecuteStrategyFactory;

    @Resource
    private ReactorConfig reactorConfig;

    @Resource
    private ConversationMemoryManager conversationMemoryManager;

    @Resource
    private PlanCheckpointCoordinator planCheckpointCoordinator;

    @Override
    public void execute(AgentRequest request, AgentSessionStream stream) throws Exception {
        AgentSessionPrinter printer = new AgentSessionPrinter(stream, request,
                request == null ? null : request.getAgentType());
        DefaultPlanSolveAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                DefaultPlanSolveAgentExecuteStrategyFactory.DynamicContext.builder()
                        .printer(printer)
                        .build();
        try {
            prepareResumeRequest(request);
            String originalQuery = request == null ? null : request.getQuery();
            if (request != null) {
                request.setOriginalQuery(originalQuery);
            }
            enrichHistoryDialogue(request);
            applyOutputStyle(request);
            StrategyHandler<AgentRequest, DefaultPlanSolveAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                    = defaultPlanSolveAgentExecuteStrategyFactory.armoryStrategyHandler();
            String result = executeHandler.apply(request, dynamicContext);
            log.info("{} PlanSolveAgent execution finished resultChars={}",
                    request == null ? null : request.getRequestId(), result == null ? 0 : result.length());
            persistTurn(request, originalQuery);
        } catch (Exception e) {
            String errorCode = "PLAN_SOLVE_EXECUTE_ERROR";
            String errorMessage = StringUtils.abbreviate(
                    StringUtils.defaultIfBlank(e.getMessage(), "Plan-Solve 执行失败"), 500);
            ExecutionLedgerRunSupport.finishRun(
                    dynamicContext.getAgentContext(),
                    ExecutionLedgerConstants.STATUS_FAILED,
                    null,
                    errorCode,
                    errorMessage
            );
            Map<String, Object> terminal = new LinkedHashMap<>();
            terminal.put("taskSummary", errorMessage);
            terminal.put("status", "FAILED");
            terminal.put("runStatus", "FAILED");
            terminal.put("errorCode", errorCode);
            terminal.put("errorMessage", errorMessage);
            terminal.put("errorMsg", errorMessage);
            printer.send("result", terminal);
            log.error("{} PlanSolveAgent execution failed errorCode={} errorType={}",
                    request == null ? null : request.getRequestId(), errorCode, e.getClass().getSimpleName());
        }
    }

    private void prepareResumeRequest(AgentRequest request) {
        if (request == null || StringUtils.isBlank(request.getResumeCheckpointId())) {
            return;
        }
        PlanExecutionCheckpoint checkpoint = planCheckpointCoordinator.inspectForResume(
                request.getResumeCheckpointId(), request.getOwnerId(), request.getSessionId());
        if (checkpoint.getResumedByRequestId() != null
                && !request.getRequestId().equals(checkpoint.getResumedByRequestId())) {
            throw new IllegalStateException("Checkpoint has already been consumed by another request");
        }
        PlanCheckpointState state = checkpoint.getState();
        if (state == null || StringUtils.isBlank(state.getOriginalQuery())) {
            throw new IllegalStateException("Checkpoint has no reproducible request snapshot");
        }
        request.setQuery(state.getOriginalQuery());
        request.setModelId(state.getModelId());
        request.setOutputStyle(state.getOutputStyle());
    }

    /**
     * 按前端 outputStyle(html/docs/ppt/table) 追加输出格式指令到 query。
     * 与 ReactAgentExecuteStrategy 保持一致，否则深度思考(Plan-Solve)模式选择的输出格式会被忽略、始终产出 HTML。
     */
    private void applyOutputStyle(AgentRequest request) {
        if (request == null || StringUtils.isEmpty(request.getOutputStyle())) {
            return;
        }
        String userQuery = StringUtils.defaultIfBlank(request.getOriginalQuery(), request.getQuery());
        if (StringUtils.isNotBlank(userQuery) && EXPLICIT_NO_TOOLS.matcher(userQuery).find()) {
            log.info("{} skip output-style tool instruction because user explicitly disabled tools",
                    request.getRequestId());
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
