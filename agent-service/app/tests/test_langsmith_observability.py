from __future__ import annotations

from contextlib import nullcontext

import pytest
from langgraph.types import Command

import service.observability.langsmith as langsmith_observability
from agents.graph import _traced_node
from router.run_rt import _run_graph_with_progress_heartbeat


class _TracingContext:
    def __init__(self) -> None:
        self.exit_args: tuple[object, ...] | None = None

    def __enter__(self) -> None:
        return None

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.exit_args = (exc_type, exc, traceback)


def test_tracing_context_preserves_application_exception(monkeypatch: pytest.MonkeyPatch) -> None:
    context = _TracingContext()
    monkeypatch.setattr(langsmith_observability, "tracing_context", lambda **_: context)

    with pytest.raises(RuntimeError, match="application failure"):
        with langsmith_observability.langsmith_tracing_context():
            raise RuntimeError("application failure")

    assert context.exit_args is not None
    assert context.exit_args[0] is RuntimeError


def test_tracing_context_does_not_require_langsmith_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(langsmith_observability.settings, "LANGSMITH_TRACING_ENABLED", False)
    monkeypatch.setattr(langsmith_observability.settings, "LANGSMITH_API_KEY", None)
    with langsmith_observability.langsmith_tracing_context():
        pass


def test_trace_metadata_from_state_preserves_explicit_research_mode() -> None:
    metadata = langsmith_observability.trace_metadata_from_state(
        {
            "run_id": "run_test",
            "user_query": "AMR 论文综述",
            "source": "eval",
            "intake_draft": {
                "research_mode": "academic",
                "analysis_archetype": "landscape",
                "report_depth": "deep",
                "response_language": "zh",
            },
        },
        node_name="researcher",
    )
    assert metadata["run_id"] == "run_test"
    assert metadata["research_mode"] == "academic"
    assert metadata["analysis_archetype"] == "landscape"
    assert metadata["report_depth"] == "deep"
    assert metadata["source"] == "eval"
    assert metadata["node"] == "researcher"


def test_eval_source_uses_full_sampling_client_when_enabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    ui_client = object()
    eval_client = object()
    monkeypatch.setattr(langsmith_observability, "_client", ui_client)
    monkeypatch.setattr(langsmith_observability, "_evaluation_client", eval_client)
    monkeypatch.setattr(
        langsmith_observability.settings,
        "LANGSMITH_EVALUATION_ENABLED",
        True,
    )

    assert langsmith_observability.langsmith_client_for_source("ui") is ui_client
    assert langsmith_observability.langsmith_client_for_source("eval") is eval_client


@pytest.mark.asyncio
async def test_traced_node_attaches_node_metadata_before_span(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_traceable_node_call(**kwargs: object) -> dict[str, object]:
        captured.update(kwargs)
        return {"status": "running"}

    monkeypatch.setattr("agents.graph.traceable_node_call", fake_traceable_node_call)

    async def node(state: dict[str, object]) -> dict[str, object]:
        return state

    result = await _traced_node("researcher", node)(
        {"run_id": "run_test", "intake_draft": {"research_mode": "academic"}}
    )

    assert result == {"status": "running"}
    extra = captured["langsmith_extra"]
    assert isinstance(extra, dict)
    assert extra["metadata"]["node"] == "researcher"
    assert "researcher" in extra["tags"]


@pytest.mark.asyncio
async def test_traced_node_inherits_eval_source_for_partial_send_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}
    eval_client = object()

    async def fake_traceable_node_call(**kwargs: object) -> dict[str, object]:
        captured.update(kwargs)
        return {"status": "running"}

    monkeypatch.setattr("agents.graph.traceable_node_call", fake_traceable_node_call)
    monkeypatch.setattr(
        "agents.graph.current_trace_metadata",
        lambda: {
            "source": "eval",
            "research_mode": "academic",
            "report_depth": "deep",
            "response_language": "zh",
        },
    )
    monkeypatch.setattr(
        "agents.graph.langsmith_client_for_source",
        lambda source: eval_client if source == "eval" else None,
    )

    async def node(state: dict[str, object]) -> dict[str, object]:
        return state

    result = await _traced_node("researcher", node)(
        {"run_id": "run_eval", "pending_tool_args": {"topic": "AMR benchmark"}}
    )

    assert result == {"status": "running"}
    extra = captured["langsmith_extra"]
    assert isinstance(extra, dict)
    assert extra["client"] is eval_client
    assert extra["metadata"]["source"] == "eval"
    assert extra["metadata"]["research_mode"] == "academic"
    assert extra["metadata"]["report_depth"] == "deep"
    assert extra["metadata"]["response_language"] == "zh"


@pytest.mark.asyncio
async def test_graph_resume_inherits_eval_source_from_checkpoint(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}
    eval_client = object()

    class Snapshot:
        values = {
            "run_id": "run_eval_resume",
            "source": "eval",
            "user_query": "AMR paper survey",
            "intake_draft": {
                "research_mode": "academic",
                "report_depth": "deep",
                "response_language": "zh",
            },
        }

    class FakeGraph:
        async def aget_state(self, config: dict[str, object]) -> Snapshot:
            assert config["configurable"] == {"thread_id": "run_eval_resume"}
            return Snapshot()

    async def fake_traceable_graph_call(**kwargs: object) -> dict[str, object]:
        captured.update(kwargs)
        return {"status": "running"}

    monkeypatch.setattr("router.run_rt.traceable_graph_call", fake_traceable_graph_call)
    monkeypatch.setattr("router.run_rt.langsmith_tracing_context", nullcontext)
    monkeypatch.setattr(
        "router.run_rt.langsmith_client_for_source",
        lambda source: eval_client if source == "eval" else None,
    )

    result = await _run_graph_with_progress_heartbeat(
        run_id="run_eval_resume",
        phase="intake_resume",
        graph=FakeGraph(),
        config={"configurable": {"thread_id": "run_eval_resume"}},
        graph_input=Command(resume={"text": "continue"}),
    )

    assert result == {"status": "running"}
    extra = captured["langsmith_extra"]
    assert isinstance(extra, dict)
    assert extra["client"] is eval_client
    assert extra["metadata"]["source"] == "eval"
    assert extra["metadata"]["research_mode"] == "academic"
    assert extra["metadata"]["report_depth"] == "deep"
