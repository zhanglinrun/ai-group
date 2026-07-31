package com.linrun.agent.eval.runner;

public interface TraceIdResolver {
    String resolve(EvalRunObservation observation) throws Exception;
}
