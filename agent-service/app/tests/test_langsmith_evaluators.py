from __future__ import annotations

from eval.langsmith.evaluators import (
    citation_grounding_evaluator,
    duplicate_task_evaluator,
    error_explainability_evaluator,
    evidence_coverage_evaluator,
    research_mode_evaluator,
    report_shape_evaluator,
    trace_completeness_evaluator,
)
from eval.langsmith.target import _timeout_seconds, agent_target
from eval.langsmith.run_eval import _result_rows


def test_langsmith_evaluators_score_grounded_report() -> None:
    outputs = {
        "evidence_ids": ["ev_1"],
        "metrics": {"evidence_dimension_coverage_rate": 1.0, "evidence_count_total": 1},
        "report": {
            "content_json": {
                "sections": [
                    {"section_id": "feature", "content_markdown": "grounded", "evidence_refs": ["ev_1"]}
                ]
            }
        },
    }
    inputs = {"expected_sections": ["feature"]}

    assert evidence_coverage_evaluator(inputs, outputs)["score"] == 1.0
    assert citation_grounding_evaluator(inputs, outputs)["score"] == 1.0
    assert report_shape_evaluator(inputs, outputs)["score"] == 1.0


def test_langsmith_target_accepts_agent_evidence_array(monkeypatch) -> None:
    class FakeResponse:
        def __init__(self, payload):
            self._payload = payload

        def raise_for_status(self):
            return None

        def json(self):
            return self._payload

    class FakeClient:
        def __init__(self, *args, **kwargs):
            del args
            assert kwargs["headers"]["X-Agent-Source"] == "eval"

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def post(self, path, json):
            assert path == "/api/runs"
            assert json["focus_dimensions"] == ["pricing"]
            return FakeResponse({"run_id": "run_eval", "status": "running"})

        def get(self, path):
            if path == "/api/runs/run_eval":
                return FakeResponse({"status": "completed"})
            if path.endswith("/report"):
                return FakeResponse({"content_json": {"sections": []}})
            if path.endswith("/metrics"):
                return FakeResponse({"evidence_count_total": 0})
            if path.endswith("/evidence"):
                return FakeResponse([{"evidence_id": "ev_1"}])
            raise AssertionError(path)

    monkeypatch.setattr("eval.langsmith.target.httpx.Client", FakeClient)
    result = agent_target({"user_query": "test", "focus_dimensions": ["pricing"]})
    assert result["run_id"] == "run_eval"
    assert result["evidence_ids"] == ["ev_1"]
    assert result["research_mode"] == "general"


def test_langsmith_target_uses_longer_default_timeout_for_deep_runs(monkeypatch) -> None:
    monkeypatch.setenv("AGENT_EVAL_TIMEOUT_SECONDS", "900")
    monkeypatch.delenv("AGENT_EVAL_DEEP_TIMEOUT_SECONDS", raising=False)

    assert _timeout_seconds({"report_depth": "quick"}) == 900.0
    assert _timeout_seconds({"report_depth": "deep"}) == 1800.0

    monkeypatch.setenv("AGENT_EVAL_DEEP_TIMEOUT_SECONDS", "2400")
    assert _timeout_seconds({"report_depth": "deep"}) == 2400.0


def test_research_mode_evaluator_uses_propagated_mode_without_agent_runtime() -> None:
    result = research_mode_evaluator(
        {"research_mode": "academic", "user_query": "AMR paper survey"},
        {
            "research_mode": "general",
            "detail": {"intake_draft": {"research_mode": "academic"}},
        },
    )

    assert result["score"] == 1.0
    assert result["value"]["actual"] == "academic"


def test_research_mode_evaluator_allows_academic_scope_boundary_disclaimer() -> None:
    result = research_mode_evaluator(
        {
            "research_mode": "academic",
            "user_query": "AMR paper survey",
            "forbidden_terms": ["价格", "厂商报价", "用户反馈"],
        },
        {
            "research_mode": "academic",
            "report": {
                "content_markdown": (
                    "本报告未提供可验证的价格、厂商报价或用户反馈证据，"
                    "因此不做商业结论。"
                )
            },
        },
    )

    assert result["score"] == 1.0
    assert result["value"]["forbidden_hits"] == []


def test_research_mode_evaluator_rejects_positive_commercial_claim() -> None:
    result = research_mode_evaluator(
        {
            "research_mode": "academic",
            "user_query": "AMR paper survey",
            "forbidden_terms": ["价格"],
        },
        {
            "research_mode": "academic",
            "report": {"content_markdown": "该产品价格为每月 99 元。"},
        },
    )

    assert result["score"] == 0.0
    assert result["value"]["forbidden_hits"] == ["价格"]


def test_result_rows_supports_current_and_legacy_langsmith_containers() -> None:
    current_rows = [{"run": "current"}]

    class CurrentResults:
        def __iter__(self):
            return iter(current_rows)

    class LegacyResults:
        def get_results(self):
            return [{"run": "legacy"}]

    assert _result_rows(CurrentResults()) == current_rows
    assert _result_rows(LegacyResults()) == [{"run": "legacy"}]


def test_trace_completeness_requires_full_flow_nodes_and_llm_metadata() -> None:
    agent_names = {
        "intake_agent",
        "planner_agent",
        "supervisor",
        "discovery",
        "replanner",
        "researcher",
        "analyst",
        "writer",
        "qa",
    }
    outputs = {
        "trace": {
            "steps": [{"agent_name": name} for name in agent_names],
            "timeline": [{"kind": "step"}],
            "llm_calls": [{"id": "llm_1", "provider": "openai", "model_name": "gpt"}],
        }
    }

    result = trace_completeness_evaluator({"competitors": []}, outputs)

    assert result["score"] == 1.0
    outputs["trace"]["steps"] = [
        item for item in outputs["trace"]["steps"] if item["agent_name"] != "qa"
    ]
    assert trace_completeness_evaluator({"competitors": []}, outputs)["score"] == 0.0


def test_trace_completeness_accepts_discovery_degraded_without_researcher() -> None:
    agent_names = {
        "intake_agent",
        "planner_agent",
        "supervisor",
        "discovery",
        "replanner",
        "analyst",
        "writer",
        "qa",
    }
    outputs = {
        "detail": {"competitors": []},
        "trace": {
            "steps": [{"agent_name": name} for name in agent_names],
            "timeline": [{"kind": "step"}],
            "llm_calls": [{"id": "llm_1", "provider": "openai", "model_name": "gpt"}],
        },
    }

    assert trace_completeness_evaluator({"competitors": []}, outputs)["score"] == 1.0


def test_duplicate_task_and_error_explainability_gates() -> None:
    detail = {
        "status_reason": "search provider quota exhausted",
        "plan_tree": {"tasks": [{"task_id": "task_1"}, {"task_id": "task_1"}]},
    }
    outputs = {"status": "degraded", "detail": detail}

    assert duplicate_task_evaluator({}, outputs)["score"] == 0.0
    assert error_explainability_evaluator({}, outputs)["score"] == 1.0

    detail["status_reason"] = "invalid internal service credential"
    assert error_explainability_evaluator({}, outputs)["score"] == 0.0
