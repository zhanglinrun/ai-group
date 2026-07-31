package com.linrun.agent.eval.runner;

import com.linrun.agent.eval.dataset.EvalCase;

public interface EvalCaseRunner {
    EvalRunObservation run(EvalCase evalCase, int trial) throws Exception;
}
