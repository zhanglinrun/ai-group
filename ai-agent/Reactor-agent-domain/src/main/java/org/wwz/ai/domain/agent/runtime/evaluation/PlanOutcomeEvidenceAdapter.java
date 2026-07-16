package org.wwz.ai.domain.agent.runtime.evaluation;

import java.util.List;

/**
 * Application adapter that translates trusted runtime state into outcome evidence.
 *
 * <p>Examples include a test-runner exit status, a citation resolver response, or
 * an artifact registry path. The default Plan-Solve path verifies explicitly registered
 * artifacts; additional test or citation requirements must still opt in through structured
 * runtime state. Implementations must never infer required outcomes from natural-language text.</p>
 */
@FunctionalInterface
public interface PlanOutcomeEvidenceAdapter {

    List<PlanOutcomeEvidence> collect(PlanEvaluationRequest request) throws Exception;
}
