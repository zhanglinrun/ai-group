from __future__ import annotations

import argparse
from hashlib import sha256
import json
import os
import sys
from pathlib import Path
from typing import Any

# Support both documented invocation styles and runtime imports performed by
# evaluators (for example schemas.intake) after an experiment has started.
_SERVICE_ROOT = Path(__file__).resolve().parents[2]
for _import_root in (_SERVICE_ROOT, _SERVICE_ROOT / "app"):
    _import_path = str(_import_root)
    if _import_path not in sys.path:
        sys.path.insert(0, _import_path)

from langsmith import Client

from eval.langsmith.evaluators import (
    duplicate_evidence_evaluator,
    duplicate_task_evaluator,
    error_explainability_evaluator,
    citation_grounding_evaluator,
    evidence_coverage_evaluator,
    make_llm_judge,
    research_mode_evaluator,
    report_shape_evaluator,
    terminal_success_evaluator,
    trace_completeness_evaluator,
)
from eval.langsmith.target import agent_target, full_flow_agent_target


def _load_examples(path: Path) -> list[dict[str, Any]]:
    examples: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            examples.append(json.loads(line))
    return examples


def _get_or_create_dataset(client: Client, name: str, examples: list[dict[str, Any]]) -> None:
    existing = next(iter(client.list_datasets(dataset_name=name, limit=1)), None)
    if existing is None:
        dataset = client.create_dataset(
            name,
            description="AI Group deep-research regression set for LangGraph Agent runs.",
            metadata={"owner": "agent-service", "purpose": "deep-research"},
        )
        for example in examples:
            client.create_example(
                inputs=example["inputs"],
                outputs=example.get("outputs", {}),
                dataset_id=dataset.id,
                metadata={"source": "repo", "version": "v1"},
            )
        return
    try:
        existing_examples = list(client.list_examples(dataset_id=existing.id))
        known = {
            json.dumps(example.inputs, ensure_ascii=False, sort_keys=True)
            for example in existing_examples
            if isinstance(example.inputs, dict)
        }
    except Exception as exc:  # pragma: no cover - SDK/network dependent
        print(f"Could not inspect existing dataset examples: {exc}")
        known = set()
    added = 0
    for example in examples:
        signature = json.dumps(example["inputs"], ensure_ascii=False, sort_keys=True)
        if signature in known:
            continue
        client.create_example(
            inputs=example["inputs"],
            outputs=example.get("outputs", {}),
            dataset_id=existing.id,
            metadata={"source": "repo", "version": "v2"},
        )
        added += 1
    print(f"Using LangSmith dataset: {name} ({existing.id}); added={added}")


def _result_rows(results: Any) -> list[dict[str, Any]]:
    """Collect rows across LangSmith SDK result-container versions."""

    get_results = getattr(results, "get_results", None)
    if callable(get_results):
        return list(get_results())
    return list(results)


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate the AI Group Agent with LangSmith.")
    parser.add_argument(
        "--dataset-file",
        type=Path,
        default=Path(__file__).parent / "datasets" / "deep_research.jsonl",
    )
    parser.add_argument("--smoke", action="store_true", help="Use the 3-example smoke dataset.")
    parser.add_argument("--formal", action="store_true", help="Use the formal release-gate dataset.")
    parser.add_argument("--mode", choices=("full_flow", "direct"), default=os.getenv("AGENT_EVAL_TARGET_MODE", "full_flow"))
    parser.add_argument("--dataset-name", default=None)
    parser.add_argument("--max-concurrency", type=int, default=int(os.getenv("LANGSMITH_EVAL_CONCURRENCY", "2")))
    parser.add_argument("--with-llm-judge", action="store_true")
    parser.add_argument("--fail-under", type=float, default=None, help="Fail when the hard-metric mean is below this score.")
    parser.add_argument("--output-json", type=Path, default=None)
    parser.add_argument(
        "--existing-experiment",
        default=None,
        help="Re-run evaluators and summarize an existing LangSmith experiment without invoking the Agent target.",
    )
    args = parser.parse_args()

    if args.smoke and args.formal:
        raise SystemExit("--smoke and --formal are mutually exclusive")
    if args.smoke:
        args.dataset_file = Path(__file__).parent / "datasets" / "deep_research.jsonl"
    elif args.formal:
        args.dataset_file = Path(__file__).parent / "datasets" / "formal_deep_research.jsonl"

    client = Client()
    examples = _load_examples(args.dataset_file)
    dataset_scope = "formal" if args.formal else "smoke" if args.smoke else "custom"
    dataset_base = os.getenv("LANGSMITH_DATASET_NAME", "ai-group-deep-research")
    dataset_digest = sha256(args.dataset_file.read_bytes()).hexdigest()[:8]
    dataset_name = args.dataset_name or f"{dataset_base}-{dataset_scope}-{dataset_digest}"
    _get_or_create_dataset(client, dataset_name, examples)
    evaluators = [
        terminal_success_evaluator,
        research_mode_evaluator,
        evidence_coverage_evaluator,
        citation_grounding_evaluator,
        report_shape_evaluator,
        trace_completeness_evaluator,
        duplicate_evidence_evaluator,
        duplicate_task_evaluator,
        error_explainability_evaluator,
    ]
    judge_model = os.getenv("LANGSMITH_JUDGE_MODEL")
    if args.with_llm_judge and judge_model:
        evaluators.append(make_llm_judge(judge_model))
    elif args.with_llm_judge:
        raise SystemExit("--with-llm-judge requires LANGSMITH_JUDGE_MODEL")

    evaluation_metadata = {
        "stack": "langgraph",
        "service": "agent-service",
        "target_mode": args.mode,
        "dataset_scope": dataset_scope,
    }
    if args.existing_experiment:
        results = client.evaluate(
            args.existing_experiment,
            evaluators=evaluators,
            metadata={**evaluation_metadata, "rescore": True},
            max_concurrency=args.max_concurrency,
            blocking=True,
        )
    else:
        target = full_flow_agent_target if args.mode == "full_flow" else agent_target
        results = client.evaluate(
            target,
            data=dataset_name,
            evaluators=evaluators,
            experiment_prefix=os.getenv("LANGSMITH_EXPERIMENT_PREFIX", "ai-group-agent"),
            metadata=evaluation_metadata,
            max_concurrency=args.max_concurrency,
            blocking=True,
            upload_results=True,
        )
    rows = _result_rows(results)
    score_map: dict[str, list[float]] = {}
    run_ids: list[str] = []
    sample_results: list[dict[str, Any]] = []
    for row in rows:
        run = row.get("run")
        langsmith_run_id = str(getattr(run, "id", "")) if run is not None else ""
        run_outputs = getattr(run, "outputs", None) if run is not None else None
        agent_run_id = (
            str(run_outputs.get("run_id"))
            if isinstance(run_outputs, dict) and run_outputs.get("run_id")
            else ""
        )
        if agent_run_id:
            run_ids.append(agent_run_id)
        evaluation_results = row.get("evaluation_results", {}).get("results", [])
        evaluator_details: list[dict[str, Any]] = []
        for evaluation in evaluation_results:
            key = str(getattr(evaluation, "key", ""))
            score = getattr(evaluation, "score", None)
            if key and isinstance(score, (int, float)):
                score_map.setdefault(key, []).append(float(score))
            evaluator_details.append(
                {
                    "key": key,
                    "score": float(score) if isinstance(score, (int, float)) else None,
                    "value": getattr(evaluation, "value", None),
                    "comment": getattr(evaluation, "comment", None),
                }
            )
        sample_results.append(
            {
                "run_id": agent_run_id or None,
                "langsmith_run_id": langsmith_run_id or None,
                "status": run_outputs.get("status") if isinstance(run_outputs, dict) else None,
                "evaluators": evaluator_details,
                "failed_evaluators": [
                    item["key"] for item in evaluator_details if item["score"] is not None and item["score"] < 1.0
                ],
            }
        )
    averages = {key: sum(scores) / len(scores) for key, scores in score_map.items() if scores}
    hard_keys = (
        "terminal_success",
        "research_mode",
        "evidence_coverage",
        "citation_grounding",
        "report_shape",
        "trace_completeness",
        "duplicate_evidence",
        "duplicate_task",
        "error_explainability",
    )
    hard_mean = sum(averages.get(key, 0.0) for key in hard_keys) / len(hard_keys)
    gate = {
        "passed": True,
        "hard_mean": hard_mean,
        "thresholds": {
            "terminal_success": 0.95,
            "evidence_coverage": 0.80,
            "citation_grounding": 0.80,
            "report_shape": 1.0,
            "trace_completeness": 1.0,
            "research_mode": 1.0,
            "duplicate_evidence": 1.0,
            "duplicate_task": 1.0,
            "error_explainability": 1.0,
        },
    }
    # Every configured threshold is a hard gate.  ``--formal`` selects the
    # release dataset; it must not change the meaning of the quality contract.
    gate["passed"] = all(
        averages.get(key, 0.0) >= threshold
        for key, threshold in gate["thresholds"].items()
    )
    if args.fail_under is not None and hard_mean < args.fail_under:
        gate["passed"] = False
    summary = {
        "experiment_name": results.experiment_name,
        "experiment_url": results.url,
        "dataset": dataset_name,
        "dataset_file": str(args.dataset_file),
        "target_mode": args.mode,
        "rescored_existing_experiment": bool(args.existing_experiment),
        "example_count": len(rows),
        "run_ids": run_ids,
        "samples": sample_results,
        "averages": averages,
        "gate": gate,
        "llm_judge_enabled": bool(args.with_llm_judge),
        "llm_judge_is_warning_only": True,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if args.output_json is not None:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if not gate["passed"]:
        raise SystemExit("LangSmith evaluation gate failed")


if __name__ == "__main__":
    main()
