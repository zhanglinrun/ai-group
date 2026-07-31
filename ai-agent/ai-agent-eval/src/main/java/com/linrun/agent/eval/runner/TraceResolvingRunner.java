package com.linrun.agent.eval.runner;

import com.linrun.agent.eval.dataset.EvalCase;

/** Enriches a completed Gateway observation only; trace lookup never turns a passed run into a failed one. */
public final class TraceResolvingRunner implements EvalCaseRunner {
    private final EvalCaseRunner delegate;
    private final TraceIdResolver resolver;

    public TraceResolvingRunner(EvalCaseRunner delegate, TraceIdResolver resolver) {
        this.delegate = delegate;
        this.resolver = resolver;
    }

    @Override
    public EvalRunObservation run(EvalCase evalCase, int trial) throws Exception {
        EvalRunObservation observation = delegate.run(evalCase, trial);
        if (!observation.traceId().isBlank() || observation.runId().isBlank()) {
            return observation;
        }
        try {
            return observation.withTraceId(resolver.resolve(observation));
        } catch (Exception ignored) {
            return observation;
        }
    }
}
