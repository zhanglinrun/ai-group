from __future__ import annotations

from types import SimpleNamespace

import pytest

from agents.nodes.replanner import replanner_node
from schemas.agent_outputs import ReplannerOutput
from schemas.intake import RunIntakeDraft
from service.event_bus import RunEventType
from service.llm.prompts import REPLANNER_SYSTEM_PROMPT
from service.llm.response import LLMResponse


def _fake_llm_response() -> LLMResponse:
    return LLMResponse(
        model_slot="research",
        provider="fake",
        model_name="fake-replanner-model",
        prompt_preview="fake replanner prompt",
        prompt_hash="fake_hash",
        content={},
        prompt_tokens=1,
        completion_tokens=1,
        latency_ms=1,
        error=None,
    )


def test_replanner_prompt_forbids_replacing_existing_research_competitors() -> None:
    assert "Never remove or replace existing protected research competitors" in REPLANNER_SYSTEM_PROMPT


@pytest.mark.asyncio
async def test_replanner_revises_plan_and_emits_event(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    emitted: list[tuple[RunEventType, str | None, dict[str, object]]] = []

    async def _fake_complete_structured(**_: object) -> SimpleNamespace:
        value = ReplannerOutput.parse_llm_content(
            {
                "rationale": "Discovery added new competitors; rebalance unfinished work.",
                "tasks": [
                    {
                        "stage": "research",
                        "title": "调研 Cursor",
                        "description": "补齐 Cursor 的关键证据。",
                        "competitor_id": "Cursor",
                        "focus_dimensions": ["feature", "pricing"],
                    },
                    {
                        "stage": "research",
                        "title": "调研 Windsurf",
                        "description": "纳入新发现竞品的证据采集。",
                        "competitor_id": "Windsurf",
                        "focus_dimensions": ["feature", "pricing"],
                    },
                    {
                        "stage": "analyze",
                        "title": "跨竞品对比分析",
                        "description": "对比关键维度并输出差异化结论。",
                        "competitor_id": None,
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                    },
                    {
                        "stage": "write",
                        "title": "生成竞品分析报告",
                        "description": "产出可执行建议。",
                        "competitor_id": None,
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                    },
                ],
            },
            draft=RunIntakeDraft(
                user_query="TRAE 对标 Cursor",
                analysis_archetype="comparison",
                competitors_explicit=["Cursor"],
            ),
        )
        return SimpleNamespace(value=value, llm_response=_fake_llm_response())

    async def _fake_persist_replanner_step(**_: object) -> str:
        return "step_replanner_test"

    async def _fake_persist_plan_tree_to_run(**_: object) -> None:
        return None

    async def _fake_emit_run_event(
        *,
        run_id: str,
        event_type: RunEventType,
        step_id: str | None = None,
        payload: dict[str, object] | None = None,
    ) -> None:
        del run_id
        emitted.append((event_type, step_id, dict(payload or {})))

    monkeypatch.setattr("agents.nodes.replanner.complete_structured", _fake_complete_structured)
    monkeypatch.setattr("agents.nodes.replanner._persist_replanner_step", _fake_persist_replanner_step)
    monkeypatch.setattr(
        "agents.nodes.replanner._persist_plan_tree_to_run",
        _fake_persist_plan_tree_to_run,
    )
    monkeypatch.setattr("agents.nodes.replanner.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.replanner._resolve_session_factory", lambda _: object())

    new_state = await replanner_node(
        {
            "run_id": "run_test",
            "competitors": ["Cursor"],
            "plan_tree": {
                "plan_id": "plan_test",
                "tasks": [
                    {
                        "task_id": "ptask_discover",
                        "stage": "discover",
                        "title": "发现赛道头部竞品",
                        "description": "discover",
                        "competitor_id": None,
                        "focus_dimensions": ["feature", "pricing"],
                        "source": "agent",
                        "enabled": True,
                        "priority": "normal",
                    },
                    {
                        "task_id": "ptask_research_cursor",
                        "stage": "research",
                        "title": "调研 Cursor",
                        "description": "research",
                        "competitor_id": "Cursor",
                        "focus_dimensions": ["feature", "pricing"],
                        "source": "agent",
                        "enabled": True,
                        "priority": "normal",
                    },
                    {
                        "task_id": "ptask_analyze",
                        "stage": "analyze",
                        "title": "分析",
                        "description": "analyze",
                        "competitor_id": None,
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "source": "agent",
                        "enabled": True,
                        "priority": "normal",
                    },
                    {
                        "task_id": "ptask_write",
                        "stage": "write",
                        "title": "撰写",
                        "description": "write",
                        "competitor_id": None,
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "source": "agent",
                        "enabled": True,
                        "priority": "normal",
                    },
                ],
                "rationale": "initial",
                "version": 1,
                "confirmed_at": "2026-01-01T00:00:00+00:00",
            },
            "intake_draft": RunIntakeDraft(
                user_query="TRAE 对标 Cursor",
                user_role="pm",
                analysis_intent="TRAE 对标 Cursor",
                competitors_explicit=["Cursor"],
            ),
            "discovered_competitors": ["Cursor", "Windsurf"],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "replan_count": 0,
        }
    )

    assert new_state["replan_count"] == 1
    revised_plan = new_state.get("plan_tree")
    assert revised_plan is not None
    revised_tasks = revised_plan.tasks if hasattr(revised_plan, "tasks") else revised_plan["tasks"]
    assert [task.stage for task in revised_tasks].count("research") == 2
    assert len(emitted) == 1
    event_type, step_id, payload = emitted[0]
    assert event_type == RunEventType.PLAN_REVISED
    assert step_id == "step_replanner_test"
    assert payload["plan_id"] == "plan_test"
    assert payload["version"] == 2
    assert payload["trigger_reason"] == "discovery"
    assert isinstance(payload["plan_tree"], dict)
    assert payload["task_count"] == len(payload["plan_tree"]["tasks"])
    research_competitors = [
        item["competitor_id"]
        for item in payload["plan_tree"]["tasks"]
        if item["stage"] == "research"
    ]
    assert set(research_competitors) == {"Cursor", "Windsurf"}


@pytest.mark.asyncio
async def test_replanner_skips_when_budget_reached(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def _should_not_be_called(**_: object) -> SimpleNamespace:
        raise AssertionError("complete_structured should not run when replan budget is reached")

    monkeypatch.setattr("agents.nodes.replanner.complete_structured", _should_not_be_called)
    new_state = await replanner_node(
        {
            "run_id": "run_test",
            "competitors": ["Cursor"],
            "plan_tree": {
                "tasks": [
                    {
                        "stage": "research",
                        "title": "调研 Cursor",
                        "description": "research",
                        "competitor_id": "Cursor",
                        "focus_dimensions": ["feature"],
                        "source": "agent",
                        "enabled": True,
                        "priority": "normal",
                    }
                ],
                "version": 1,
            },
            "intake_draft": RunIntakeDraft(
                user_query="debug run",
                analysis_intent="debug run",
                report_depth="debug",
            ),
            "replan_count": 1,
        }
    )

    assert new_state["replan_count"] == 1


@pytest.mark.asyncio
async def test_replanner_no_change_on_llm_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    emitted: list[tuple[RunEventType, str | None, dict[str, object]]] = []

    async def _fake_complete_structured(**_: object) -> SimpleNamespace:
        return SimpleNamespace(value=None, llm_response=_fake_llm_response())

    async def _fake_persist_replanner_step(**_: object) -> str:
        return "step_replanner_no_change"

    async def _fake_emit_run_event(
        *,
        run_id: str,
        event_type: RunEventType,
        step_id: str | None = None,
        payload: dict[str, object] | None = None,
    ) -> None:
        del run_id
        emitted.append((event_type, step_id, dict(payload or {})))

    monkeypatch.setattr("agents.nodes.replanner.complete_structured", _fake_complete_structured)
    monkeypatch.setattr("agents.nodes.replanner._persist_replanner_step", _fake_persist_replanner_step)
    monkeypatch.setattr("agents.nodes.replanner.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.replanner._resolve_session_factory", lambda _: object())

    new_state = await replanner_node(
        {
            "run_id": "run_test",
            "competitors": ["Cursor"],
            "plan_tree": {
                "tasks": [
                    {
                        "stage": "research",
                        "title": "调研 Cursor",
                        "description": "research",
                        "competitor_id": "Cursor",
                        "focus_dimensions": ["feature"],
                        "source": "agent",
                        "enabled": True,
                        "priority": "normal",
                    }
                ],
                "version": 1,
            },
            "intake_draft": RunIntakeDraft(
                user_query="comparison run",
                analysis_intent="comparison run",
                report_depth="quick",
            ),
            "replan_count": 0,
        }
    )

    assert new_state["replan_count"] == 1
    assert emitted == []


@pytest.mark.asyncio
async def test_replanner_protects_existing_state_research_tasks(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def _fake_complete_structured(**_: object) -> SimpleNamespace:
        value = ReplannerOutput.parse_llm_content(
            {
                "rationale": "Replace baseline plan.",
                "tasks": [
                    {
                        "stage": "research",
                        "title": "调研 Windsurf",
                        "description": "新增竞品。",
                        "competitor_id": "Windsurf",
                        "focus_dimensions": ["feature", "pricing"],
                    },
                    {
                        "stage": "analyze",
                        "title": "分析",
                        "description": "analyze",
                        "competitor_id": None,
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                    },
                    {
                        "stage": "write",
                        "title": "写作",
                        "description": "write",
                        "competitor_id": None,
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                    },
                ],
            },
            draft=RunIntakeDraft(
                user_query="TRAE 对标 Cursor",
                analysis_archetype="comparison",
                competitors_explicit=["Cursor"],
            ),
        )
        return SimpleNamespace(value=value, llm_response=_fake_llm_response())

    async def _fake_persist_replanner_step(**_: object) -> str:
        return "step_replanner_protected"

    async def _fake_persist_plan_tree_to_run(**_: object) -> None:
        return None

    async def _fake_emit_run_event(**_: object) -> None:
        return None

    monkeypatch.setattr("agents.nodes.replanner.complete_structured", _fake_complete_structured)
    monkeypatch.setattr("agents.nodes.replanner._persist_replanner_step", _fake_persist_replanner_step)
    monkeypatch.setattr(
        "agents.nodes.replanner._persist_plan_tree_to_run",
        _fake_persist_plan_tree_to_run,
    )
    monkeypatch.setattr("agents.nodes.replanner.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.replanner._resolve_session_factory", lambda _: object())

    new_state = await replanner_node(
        {
            "run_id": "run_test",
            "competitors": ["Cursor"],
            "discovered_competitors": ["Cursor", "Windsurf"],
            "plan_tree": {
                "plan_id": "plan_test",
                "tasks": [
                    {
                        "task_id": "ptask_research_cursor",
                        "stage": "research",
                        "title": "调研 Cursor",
                        "description": "research",
                        "competitor_id": "Cursor",
                        "focus_dimensions": ["feature", "pricing"],
                        "source": "agent",
                        "enabled": True,
                        "priority": "normal",
                    },
                    {
                        "task_id": "ptask_analyze",
                        "stage": "analyze",
                        "title": "分析",
                        "description": "analyze",
                        "competitor_id": None,
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "source": "agent",
                        "enabled": True,
                        "priority": "normal",
                    },
                    {
                        "task_id": "ptask_write",
                        "stage": "write",
                        "title": "撰写",
                        "description": "write",
                        "competitor_id": None,
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "source": "agent",
                        "enabled": True,
                        "priority": "normal",
                    },
                ],
                "rationale": "initial",
                "version": 1,
                "confirmed_at": "2026-01-01T00:00:00+00:00",
            },
            "intake_draft": RunIntakeDraft(
                user_query="TRAE 对标 Cursor",
                user_role="pm",
                analysis_intent="TRAE 对标 Cursor",
                competitors_explicit=["Cursor"],
            ),
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "replan_count": 0,
        }
    )

    revised_plan = new_state.get("plan_tree")
    assert revised_plan is not None
    revised_tasks = revised_plan.tasks if hasattr(revised_plan, "tasks") else revised_plan["tasks"]
    research_competitors = [
        task.competitor_id for task in revised_tasks if task.stage == "research"
    ]
    assert research_competitors == ["Cursor", "Windsurf"]
    assert new_state.get("competitors") == ["Windsurf"]


@pytest.mark.asyncio
async def test_replanner_fails_fast_when_plan_contains_unknown_research_competitor(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def _fake_complete_structured(**_: object) -> SimpleNamespace:
        return SimpleNamespace(value=None, llm_response=_fake_llm_response())

    monkeypatch.setattr("agents.nodes.replanner.complete_structured", _fake_complete_structured)
    monkeypatch.setattr("agents.nodes.replanner._resolve_session_factory", lambda _: object())

    with pytest.raises(ValueError, match="unexpected=.*Unknown"):
        await replanner_node(
            {
                "run_id": "run_test",
                "competitors": ["Cursor"],
                "discovered_competitors": ["Cursor"],
                "plan_tree": {
                    "tasks": [
                        {
                            "stage": "research",
                            "title": "调研 Unknown",
                            "description": "research",
                            "competitor_id": "Unknown",
                            "focus_dimensions": ["feature"],
                            "source": "agent",
                            "enabled": True,
                            "priority": "normal",
                        }
                    ],
                    "version": 1,
                },
                "intake_draft": RunIntakeDraft(
                    user_query="comparison run",
                    analysis_intent="comparison run",
                    report_depth="quick",
                ),
                "replan_count": 0,
            }
        )
