package org.wwz.ai.domain.agent.runtime.evaluation;

import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.runtime.ReactorRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.llm.LLM;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spring-AI-backed evaluator that records its call in the existing LLM ledger.
 */
public final class LlmPlanQualityJudge implements PlanQualityJudge {

    private final AgentContext context;
    private final LLM llm;
    private final double temperature;

    public LlmPlanQualityJudge(AgentContext context, PlanEvaluationPolicy policy) {
        this.context = context;
        ReactorRuntimeDependencies dependencies = requireDependencies(context);
        ReactorConfig config = dependencies.requireReactorConfig();
        this.llm = new LLM(
                dependencies.resolveEffectiveLlmSettings(context.getModelIdOverride(), policy.judgeModelName()),
                "",
                dependencies
        );
        this.temperature = policy.judgeTemperature();
    }

    @Override
    public String judge(String systemPrompt, String userPrompt, int timeoutSeconds) throws Exception {
        context.markExecutionPosition("evaluator", null);
        return llm.ask(
                        context,
                        List.of(Message.userMessage(userPrompt, null)),
                        List.of(Message.systemMessage(systemPrompt, null)),
                        false,
                        false,
                        temperature,
                        ExecutionLedgerConstants.CALL_KIND_EVALUATE
                )
                .get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
    }

    private static ReactorRuntimeDependencies requireDependencies(AgentContext context) {
        if (context == null || context.getRuntimeDependencies() == null) {
            throw new IllegalStateException("Plan evaluator requires ReactorRuntimeDependencies");
        }
        return context.getRuntimeDependencies();
    }
}
