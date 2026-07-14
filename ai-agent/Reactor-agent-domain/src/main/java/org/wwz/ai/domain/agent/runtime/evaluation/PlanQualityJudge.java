package org.wwz.ai.domain.agent.runtime.evaluation;

/**
 * Optional semantic judge. Implementations must return a JSON object.
 */
@FunctionalInterface
public interface PlanQualityJudge {

    String judge(String systemPrompt, String userPrompt, int timeoutSeconds) throws Exception;
}
