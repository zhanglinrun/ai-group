package org.wwz.ai.domain.agent.service.execute.react.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.SummaryAgent;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.dto.TaskSummaryResult;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.runtime.metrics.AgentRunMetrics;
import org.wwz.ai.domain.agent.service.execute.react.step.factory.DefaultReactAgentExecuteStrategyFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * React 逻辑树 - 步骤3：生成任务总结并发送结果
 * 基于 ReAct 执行记忆生成总结，构建结果 Map，通过 Printer 发送
 */
@Slf4j
@Service
public class SummaryResultNode extends AbstractExecuteSupport {

    @Override
    protected String doApply(AgentRequest requestParameter, DefaultReactAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("React Step3: Summary and send result for requestId: {}", requestParameter.getRequestId());

        AgentContext agentContext = dynamicContext.getAgentContext();

        SummaryAgent summary = dynamicContext.getSummary();

        if (agentContext == null || summary == null || dynamicContext.getExecutor() == null) {
            throw new IllegalStateException("React Step3: agentContext/executor/summary is null, Step2 must run first.");
        }

        TaskSummaryResult result = summary.summaryTaskResult(
                dynamicContext.getExecutor().getMemory().getMessages(),
                requestParameter.getQuery()
        );

        Map<String, Object> taskResult = new HashMap<>();
        taskResult.put("taskSummary", result.getTaskSummary());

        if (CollectionUtils.isEmpty(result.getFiles())) {
            List<File> fileResponses = agentContext.getReversedVisibleArtifactFiles();
            if (!CollectionUtils.isEmpty(fileResponses)) {
                taskResult.put("fileList", fileResponses);
            }
        } else {
            taskResult.put("fileList", result.getFiles());
        }

        // 展示级 run 元数据（模型 / 耗时），随最终帧下发供前端渲染 chips
        Map<String, Object> metrics = AgentRunMetrics.fromContext(
                agentContext,
                agentContext.getRuntimeDependencies().requireReactorConfig().getReactModelName());
        if (!metrics.isEmpty()) {
            taskResult.put(AgentRunMetrics.KEY, metrics);
        }

        agentContext.getPrinter().send("result", taskResult);
        // 执行链路可能吞掉异常后降级到总结，此时 run 终态必须记为失败，供历史回放与配额结算使用
        boolean runFailed = agentContext.isRunFailed();
        ExecutionLedgerRunSupport.finishRun(
                agentContext,
                runFailed ? ExecutionLedgerConstants.STATUS_FAILED : ExecutionLedgerConstants.STATUS_SUCCESS,
                result.getTaskSummary(),
                runFailed ? "REACT_RUN_DEGRADED" : null,
                runFailed ? "执行链路捕获异常后降级完成，详见服务日志" : null
        );
        dynamicContext.setStep(3);

        return "success";
    }

    @Override
    public StrategyHandler<AgentRequest, DefaultReactAgentExecuteStrategyFactory.DynamicContext, String> get(
            AgentRequest requestParameter,
            DefaultReactAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return null;
    }
}
