"""Phase 3: verify the new live-run events (tool.start/finish, evidence.collected,
supervisor.decision.plan_task_ids) emit at the right boundaries.

We deliberately bypass the event bus pub/sub layer (Postgres LISTEN/NOTIFY) and
patch `emit_run_event` per module to capture the (event_type, payload) tuples
in-process. This keeps the test infra-free and focused on the emit contract.
"""
from __future__ import annotations

from typing import Any

import pytest

from agents.nodes.supervisor import _match_plan_task_ids
from agents.subgraphs.researcher import ResearcherSubState, tool_exec
from schemas.ids import make_id
from schemas.plan import PlanTask, PlanTree
from schemas.supervisor import (
    Analyze,
    ConductResearch,
    ConductResearchBatch,
    DiscoverCompetitors,
    Finalize,
    SupervisorDecision,
    Write,
)
from service.collector.base import (
    CollectorObservation,
    CollectorSnippet,
    ToolObservationResult,
)
from service.collector.errors import ChannelError
from service.event_bus import RunEventType


def _make_decision(
    *, chosen_tool: str, tool_args: dict[str, Any]
) -> SupervisorDecision:
    now = "2026-05-31T00:00:00+00:00"
    return SupervisorDecision(
        id=make_id("decision_"),
        run_id="run_phase3_test",
        iteration=1,
        chosen_tool=chosen_tool,
        tool_args=tool_args,
        reasoning_summary="phase3 test decision",
        triggered_by="user_query",
        outcome="succeeded",
        outcome_recorded_at=now,
        created_at=now,
    )


def _make_plan_tree() -> PlanTree:
    return PlanTree(
        plan_id="plan_phase3",
        version=1,
        confirmed_at="2026-05-31T00:00:00+00:00",
        tasks=[
            PlanTask(task_id="ptask_discover_1", stage="discover", title="Find competitors"),
            PlanTask(
                task_id="ptask_research_notion",
                stage="research",
                title="Research Notion",
                competitor_id="Notion",
            ),
            PlanTask(
                task_id="ptask_research_obsidian",
                stage="research",
                title="Research Obsidian",
                competitor_id="Obsidian",
            ),
            PlanTask(task_id="ptask_analyze_1", stage="analyze", title="Analyze findings"),
            PlanTask(task_id="ptask_write_1", stage="write", title="Write report"),
        ],
    )


# ---- _match_plan_task_ids: pure helper ---------------------------------------


def test_match_plan_task_ids_discover_returns_discover_tasks() -> None:
    plan_tree = _make_plan_tree().model_dump(mode="json")
    decision = _make_decision(
        chosen_tool="DiscoverCompetitors",
        tool_args=DiscoverCompetitors(
            search_queries=["competitor analysis"], domain_context="notes apps"
        ).model_dump(),
    )
    assert _match_plan_task_ids(plan_tree=plan_tree, decision=decision) == [
        "ptask_discover_1"
    ]


def test_match_plan_task_ids_research_single_competitor_matches_by_id() -> None:
    plan_tree = _make_plan_tree().model_dump(mode="json")
    decision = _make_decision(
        chosen_tool="ConductResearch",
        tool_args=ConductResearch(
            competitor_id="Notion",
            research_topic="Pricing & features",
            focus_dimensions=["pricing", "feature"],
        ).model_dump(),
    )
    assert _match_plan_task_ids(plan_tree=plan_tree, decision=decision) == [
        "ptask_research_notion"
    ]


def test_match_plan_task_ids_research_batch_returns_all_matched_competitors() -> None:
    plan_tree = _make_plan_tree().model_dump(mode="json")
    decision = _make_decision(
        chosen_tool="ConductResearchBatch",
        tool_args=ConductResearchBatch(
            topics=[
                ConductResearch(
                    competitor_id="Notion",
                    research_topic="x",
                    focus_dimensions=["pricing"],
                ),
                ConductResearch(
                    competitor_id="Obsidian",
                    research_topic="y",
                    focus_dimensions=["feature"],
                ),
                ConductResearch(
                    competitor_id="Unknown",
                    research_topic="z",
                    focus_dimensions=["feature"],
                ),
            ],
            parallelism_rationale="independent competitors",
        ).model_dump(),
    )
    matched = _match_plan_task_ids(plan_tree=plan_tree, decision=decision)
    assert sorted(matched) == ["ptask_research_notion", "ptask_research_obsidian"]


def test_match_plan_task_ids_analyze_and_write() -> None:
    plan_tree = _make_plan_tree().model_dump(mode="json")
    analyze_decision = _make_decision(
        chosen_tool="Analyze",
        tool_args=Analyze(focus_dimensions=["pricing", "feature"]).model_dump(),
    )
    write_decision = _make_decision(
        chosen_tool="Write",
        tool_args=Write(sections=["overview"]).model_dump(),
    )
    assert _match_plan_task_ids(plan_tree=plan_tree, decision=analyze_decision) == [
        "ptask_analyze_1"
    ]
    assert _match_plan_task_ids(plan_tree=plan_tree, decision=write_decision) == [
        "ptask_write_1"
    ]


def test_match_plan_task_ids_finalize_returns_empty() -> None:
    plan_tree = _make_plan_tree().model_dump(mode="json")
    decision = _make_decision(
        chosen_tool="Finalize",
        tool_args=Finalize(
            completion_reason="all_dimensions_covered", notes="ok"
        ).model_dump(),
    )
    assert _match_plan_task_ids(plan_tree=plan_tree, decision=decision) == []


def test_match_plan_task_ids_missing_plan_tree_returns_empty() -> None:
    decision = _make_decision(
        chosen_tool="Analyze",
        tool_args=Analyze(focus_dimensions=["pricing"]).model_dump(),
    )
    assert _match_plan_task_ids(plan_tree=None, decision=decision) == []
    assert _match_plan_task_ids(plan_tree={"tasks": "broken"}, decision=decision) == []


# ---- tool_exec: emits tool.start/finish around registry.invoke ---------------


class _FakeRegistrySuccess:
    def __init__(self, snippet_count: int) -> None:
        self._snippet_count = snippet_count
        self.invoke_calls: list[tuple[str, dict[str, object]]] = []

    async def invoke(
        self, action: str, *, args: dict[str, object]
    ) -> CollectorObservation:
        self.invoke_calls.append((action, dict(args)))
        snippets = [
            CollectorSnippet(
                quote=f"q{i}",
                source_type="article",
                source_url=f"https://example.com/{i}",
                source_title=f"t{i}",
                sanitized_text=f"s{i}",
                desensitized=True,
            )
            for i in range(self._snippet_count)
        ]
        return CollectorObservation(
            channel=action,
            args=dict(args),
            result=ToolObservationResult(snippets=snippets, metadata={}),
        )


class _FakeRegistryError:
    def __init__(self, message: str) -> None:
        self._message = message
        self.invoke_calls: list[tuple[str, dict[str, object]]] = []

    async def invoke(
        self, action: str, *, args: dict[str, object]
    ) -> CollectorObservation:
        self.invoke_calls.append((action, dict(args)))
        raise ChannelError(self._message)


def _install_capture(
    monkeypatch: pytest.MonkeyPatch,
    *,
    captured: list[tuple[RunEventType, str | None, dict[str, object]]],
    module_dotted: str,
) -> None:
    async def _capture(
        *,
        run_id: str,
        event_type: RunEventType,
        step_id: str | None = None,
        payload: dict[str, object] | None = None,
    ) -> None:
        del run_id
        captured.append((event_type, step_id, dict(payload or {})))

    monkeypatch.setattr(f"{module_dotted}.emit_run_event", _capture)


async def test_tool_exec_emits_start_and_finish_on_success(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[tuple[RunEventType, str | None, dict[str, object]]] = []
    _install_capture(monkeypatch, captured=captured, module_dotted="agents.subgraphs.researcher")
    fake_registry = _FakeRegistrySuccess(snippet_count=3)
    monkeypatch.setattr(
        "agents.subgraphs.researcher.get_channel_registry", lambda: fake_registry
    )

    state: ResearcherSubState = {
        "run_id": "run_phase3_tool",
        "step_id": "step_phase3_tool",
        "competitor_id": "Notion",
        "turn_count": 0,
        "pending_action_args": {
            "_action": "search_web",
            "query": "notion pricing",
            "max_results": 5,
            "dimension": "pricing",
        },
        "observations_log": [],
        "evidence_drafts": [],
        "messages": [],
        "pending_dimensions": ["pricing"],
        "queried_dimensions": [],
    }

    new_state = await tool_exec(state)

    assert len(fake_registry.invoke_calls) == 1
    invoked_tool, invoked_args = fake_registry.invoke_calls[0]
    assert invoked_tool == "search_web"
    # tool_exec now also scopes searches with competitor_id + official_hosts; assert
    # the core args as a subset so the source-scoping additions don't break this.
    assert invoked_args["query"] == "notion pricing"
    assert invoked_args["max_results"] == 5
    assert invoked_args["dimension"] == "pricing"
    assert [event_type for event_type, _, _ in captured] == [
        RunEventType.TOOL_START,
        RunEventType.TOOL_FINISH,
    ]

    start_event_type, start_step_id, start_payload = captured[0]
    assert start_event_type == RunEventType.TOOL_START
    assert start_step_id == "step_phase3_tool"
    assert start_payload["tool"] == "search_web"
    assert start_payload["competitor_id"] == "Notion"
    assert start_payload["dimension"] == "pricing"
    assert start_payload["turn"] == 1
    assert start_payload["args_summary"] == {
        "query": "notion pricing",
        "max_results": 5,
        "dimension": "pricing",
    }

    finish_event_type, finish_step_id, finish_payload = captured[1]
    assert finish_event_type == RunEventType.TOOL_FINISH
    assert finish_step_id == "step_phase3_tool"
    assert finish_payload["tool"] == "search_web"
    assert finish_payload["competitor_id"] == "Notion"
    assert finish_payload["success"] is True
    assert finish_payload["snippet_count"] == 3
    assert finish_payload["snippet_preview"] == "s0"
    assert finish_payload["source_type_distribution"] == {"article": 3}
    assert finish_payload["error"] is None
    assert isinstance(finish_payload["latency_ms"], int)
    assert finish_payload["turn"] == 1

    assert new_state["turn_count"] == 1


async def test_tool_exec_emits_finish_with_error_on_channel_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[tuple[RunEventType, str | None, dict[str, object]]] = []
    _install_capture(monkeypatch, captured=captured, module_dotted="agents.subgraphs.researcher")
    fake_registry = _FakeRegistryError("network unavailable")
    monkeypatch.setattr(
        "agents.subgraphs.researcher.get_channel_registry", lambda: fake_registry
    )

    state: ResearcherSubState = {
        "run_id": "run_phase3_tool_err",
        "step_id": "step_phase3_tool_err",
        "competitor_id": "Notion",
        "turn_count": 0,
        "pending_action_args": {
            "_action": "fetch_url",
            "url": "https://example.com/p",
            "dimension": "pricing",
        },
        "observations_log": [],
        "evidence_drafts": [],
        "messages": [],
        "pending_dimensions": ["pricing"],
        "queried_dimensions": [],
    }

    await tool_exec(state)

    assert [event_type for event_type, _, _ in captured] == [
        RunEventType.TOOL_START,
        RunEventType.TOOL_FINISH,
    ]
    _, finish_step_id, finish_payload = captured[1]
    assert finish_step_id == "step_phase3_tool_err"
    assert finish_payload["success"] is False
    assert finish_payload["snippet_count"] == 0
    assert finish_payload["source_type_distribution"] == {}
    error_text = finish_payload["error"]
    assert isinstance(error_text, str)
    assert "network unavailable" in error_text
