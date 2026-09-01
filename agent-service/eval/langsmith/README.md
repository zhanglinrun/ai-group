# LangSmith evaluation demo

This directory is the executable evaluation entry point for the LangGraph Agent. It keeps the
production graph as the target and evaluates completed HTTP runs, so the same trace can be debugged
in LangSmith and scored in a regression experiment.

## Run

Set `LANGSMITH_API_KEY`, `LANGSMITH_PROJECT`, and the Agent credentials
(`AGENT_EVAL_INTERNAL_TOKEN` plus a signed `AGENT_EVAL_IDENTITY_JWT` when identity enforcement is
enabled). The full compose file publishes the Agent's protected local diagnostic port on `8090`,
which is the default evaluation base URL. Set `AGENT_EVAL_BASE_URL=http://localhost:8080` only when
you also provide a real Gateway Sa-Token session. Start the Agent/Gateway first, then run from
`agent-service`:

```powershell
python -m eval.langsmith.run_eval --smoke --mode full_flow --output-json artifacts/langsmith-smoke.json
```

`--smoke` uses the 3-example regression set. `--formal` uses the 40-example release set
(15 academic, 10 technical, 10 commercial, and 5 general). Dataset names include the scope and
content hash, so smoke and formal examples never accumulate into the same LangSmith dataset.
`--mode full_flow` is the release path and drives Intake,
depth selection, plan confirmation, and the production graph; `--mode direct` is a faster compatibility
target for API regressions. The deterministic evaluators check terminal success, research-mode
correctness, evidence coverage, citation grounding, report shape, trace completeness, duplicate
evidence/tasks, and explainable error states. The JSON report includes every Agent `run_id`, each
evaluator score, and failed evaluator names. Add `--with-llm-judge` with
`LANGSMITH_JUDGE_MODEL` for qualitative warning-only scoring.

Quick runs use `AGENT_EVAL_TIMEOUT_SECONDS` (default 900 seconds). Deep runs allow at least 1800
seconds and can be overridden independently with `AGENT_EVAL_DEEP_TIMEOUT_SECONDS`.
If target execution finished but local summarization was interrupted, use
`--existing-experiment <name-or-id>` to re-run only the evaluators and write the JSON report without
invoking the Agent again.

Run the dedicated AMR academic smoke case before the formal suite when validating research-mode
isolation or a new search provider:

```powershell
python -m eval.langsmith.run_eval --dataset-file eval/langsmith/datasets/academic_smoke.jsonl --mode full_flow --max-concurrency 1 --fail-under 0.8 --output-json artifacts/langsmith-academic-smoke.json
```

This case forbids commercial pricing/vendor language. A search-provider outage is expected to
produce an explainable `degraded` run and a failed quality gate; it must not be reported as a
successful release evaluation.

Verify one trace after a smoke run:

```powershell
python -m eval.langsmith.verify_trace --run-id <run_id>
```

The trace verifier uses `AGENT_EVAL_INTERNAL_TOKEN` and `AGENT_EVAL_IDENTITY_JWT` for the protected
local diagnostics endpoint. It checks the complete node set plus provider/model metadata on LLM
spans. Keep credentials in environment variables only.
