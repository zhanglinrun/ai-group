package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.ExecutorAgent;
import org.wwz.ai.domain.agent.runtime.agent.PlanningAgent;
import org.wwz.ai.domain.agent.runtime.agent.SummaryAgent;
import org.wwz.ai.domain.agent.runtime.dto.Memory;
import org.wwz.ai.domain.agent.runtime.dto.TaskSummaryResult;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanEvaluationPolicy;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanEvaluationResult;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanExecutionEvaluator;
import org.wwz.ai.domain.agent.service.execute.planexecute.step.Step2PlanExecuteNode;
import org.wwz.ai.domain.agent.service.execute.planexecute.step.factory.DefaultPlanSolveAgentExecuteStrategyFactory;
import org.wwz.ai.test.domain.support.ReactorRuntimeTestSupport;

import java.util.List;

public class PlanEvaluationReplanIntegrationTest {

    @Test
    public void shouldFeedEvaluationFailureBackToPlannerAndConvergeAfterCorrection() throws Exception {
        ExecutionLedgerFixtureFactory.LedgerTestContext ledger = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ReactorConfig config = evaluationConfig();
        AgentContext context = ExecutionLedgerFixtureFactory.newAgentContext(
                "req-evaluator-replan-001", "session-evaluator-replan-001", ledger.recorder);
        context.setQuery("核验价格并给出官方来源");
        context.setPrinter(Mockito.mock(org.wwz.ai.domain.agent.runtime.printer.Printer.class));
        context.setRuntimeDependencies(ReactorRuntimeTestSupport.runtimeDependencies(config));
        ExecutionLedgerFixtureFactory.activateRun(
                context, ledger.recorder, ExecutionLedgerConstants.ENTRY_AGENT_PLAN_SOLVE);

        PlanningAgent planning = Mockito.mock(PlanningAgent.class);
        Mockito.when(planning.getState()).thenReturn(AgentState.FINISHED);
        Mockito.when(planning.getSystemPrompt()).thenReturn("");
        Mockito.when(planning.run(Mockito.anyString()))
                .thenReturn("首次核验价格", "finish");
        Mockito.when(planning.retryCurrentTask(Mockito.anyString()))
                .thenReturn("补充官方价格来源");

        ExecutorAgent executor = Mockito.mock(ExecutorAgent.class);
        Mockito.when(executor.getState()).thenReturn(AgentState.FINISHED);
        Mockito.when(executor.getMemory()).thenReturn(new Memory());
        Mockito.when(executor.run(Mockito.anyString()))
                .thenReturn("价格比较已完成，但没有来源。", "价格比较已完成，并附官方价格页来源。");

        SummaryAgent summary = Mockito.mock(SummaryAgent.class);
        Mockito.when(summary.getSystemPrompt()).thenReturn("summary {{query}}");
        Mockito.when(summary.summaryTaskResult(Mockito.anyList(), Mockito.anyString()))
                .thenReturn(TaskSummaryResult.builder().taskSummary("已核验价格并附官方来源").build());

        PlanExecutionEvaluator evaluator = Mockito.mock(PlanExecutionEvaluator.class);
        Mockito.when(evaluator.evaluate(Mockito.any(), Mockito.any()))
                .thenReturn(rejectedEvaluation(), acceptedEvaluation());

        TestStepNode node = new TestStepNode(config, planning, executor, summary, evaluator);
        node.run(
                AgentRequest.builder()
                        .requestId(context.getRequestId())
                        .sessionId(context.getSessionId())
                        .query(context.getQuery())
                        .outputStyle("html")
                        .build(),
                DefaultPlanSolveAgentExecuteStrategyFactory.DynamicContext.builder()
                        .agentContext(context)
                        .build()
        );

        ArgumentCaptor<String> plannerInputs = ArgumentCaptor.forClass(String.class);
        Mockito.verify(planning, Mockito.times(2)).run(plannerInputs.capture());
        ArgumentCaptor<String> retryInput = ArgumentCaptor.forClass(String.class);
        Mockito.verify(planning).retryCurrentTask(retryInput.capture());
        String targetedFeedback = retryInput.getValue();
        Assert.assertTrue(targetedFeedback.contains("missing source for pricing claim"));
        Assert.assertTrue(targetedFeedback.contains("cite the official pricing page"));
        Assert.assertEquals(2, context.getAgentRunState().getEvaluationCountValue());
        Assert.assertEquals(1, context.getAgentRunState().getTargetedReplanCountValue());
        Assert.assertEquals(Integer.valueOf(92), context.getAgentRunState().getLatestQualityScoreValue());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_SUCCESS),
                ledger.queryService.queryRunDetail(context.getRequestId()).getRun().getStatus());
    }

    private ReactorConfig evaluationConfig() {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "plannerMaxSteps", 5);
        ReflectionTestUtils.setField(config, "plannerModelName", "test-planner");
        ReflectionTestUtils.setField(config, "evaluatorEnabled", true);
        ReflectionTestUtils.setField(config, "evaluatorLlmJudgeEnabled", false);
        ReflectionTestUtils.setField(config, "evaluatorScoreThreshold", 75);
        ReflectionTestUtils.setField(config, "evaluatorMaxReplanRounds", 2);
        ReflectionTestUtils.setField(config, "evaluatorReflectionTokenBudget", 6000);
        return config;
    }

    private PlanEvaluationResult rejectedEvaluation() {
        return new PlanEvaluationResult(
                true, false, 48, 48, null, 45, 70, 30,
                List.of("missing source for pricing claim"),
                "rerun search and cite the official pricing page",
                false, false, 120
        );
    }

    private PlanEvaluationResult acceptedEvaluation() {
        return new PlanEvaluationResult(
                true, true, 92, 92, null, 95, 90, 90,
                List.of(), "", false, false, 100
        );
    }

    private static final class TestStepNode extends Step2PlanExecuteNode {
        private final PlanningAgent planning;
        private final ExecutorAgent executor;
        private final SummaryAgent summary;
        private final PlanExecutionEvaluator evaluator;

        private TestStepNode(ReactorConfig config,
                             PlanningAgent planning,
                             ExecutorAgent executor,
                             SummaryAgent summary,
                             PlanExecutionEvaluator evaluator) {
            this.planning = planning;
            this.executor = executor;
            this.summary = summary;
            this.evaluator = evaluator;
            ReflectionTestUtils.setField(this, "reactorConfig", config);
        }

        private String run(AgentRequest request,
                           DefaultPlanSolveAgentExecuteStrategyFactory.DynamicContext context) throws Exception {
            return doApply(request, context);
        }

        @Override
        protected PlanningAgent createPlanningAgent(AgentContext context) {
            return planning;
        }

        @Override
        protected ExecutorAgent createExecutorAgent(AgentContext context) {
            return executor;
        }

        @Override
        protected SummaryAgent createSummaryAgent(AgentContext context) {
            return summary;
        }

        @Override
        protected PlanExecutionEvaluator createPlanExecutionEvaluator(AgentContext context,
                                                                       PlanEvaluationPolicy policy) {
            return evaluator;
        }
    }
}
