from __future__ import annotations

from agents.graph import _route_after_qa
from agents.nodes.planner import _fallback_tasks
from schemas.agent_outputs import PlannerOutput
from schemas.intake import RunIntakeDraft


def test_fallback_tasks_landscape_emits_discover_and_two_analyze_tasks() -> None:
    draft = RunIntakeDraft(
        user_query="我要看 AI coding 工具赛道机会",
        analysis_intent="扫描赛道机会",
        analysis_archetype="landscape",
        competitors_explicit=["Cursor", "Windsurf"],
        competitors_discovery_mode=False,
    )

    tasks = _fallback_tasks(draft, max_competitors=8, max_dimensions=4)

    assert tasks[0].stage == "discover"
    assert [task.stage for task in tasks].count("research") == 2
    assert [task.stage for task in tasks].count("analyze") == 2
    assert tasks[-1].stage == "write"


def test_planner_output_keeps_single_analyze_for_comparison() -> None:
    content = {
        "rationale": "comparison plan",
        "tasks": [
            {
                "stage": "research",
                "title": "调研 Cursor",
                "description": "research",
                "competitor_id": "Cursor",
                "focus_dimensions": ["feature", "pricing"],
            },
            {
                "stage": "analyze",
                "title": "分析 1",
                "description": "analyze",
                "competitor_id": None,
                "focus_dimensions": ["feature", "pricing"],
            },
            {
                "stage": "analyze",
                "title": "分析 2",
                "description": "analyze",
                "competitor_id": None,
                "focus_dimensions": ["feature", "pricing"],
            },
            {
                "stage": "write",
                "title": "撰写",
                "description": "write",
                "competitor_id": None,
                "focus_dimensions": ["feature", "pricing"],
            },
        ],
    }
    parsed = PlannerOutput.parse_llm_content(
        content,
        draft=RunIntakeDraft(
            user_query="对比 Cursor 和 Windsurf",
            analysis_intent="对比 Cursor 和 Windsurf",
            analysis_archetype="comparison",
            competitors_explicit=["Cursor", "Windsurf"],
        ),
    )

    assert [task.stage for task in parsed.tasks].count("analyze") == 1


def test_planner_output_allows_two_analyze_for_landscape() -> None:
    content = {
        "rationale": "landscape plan",
        "tasks": [
            {
                "stage": "discover",
                "title": "发现赛道头部竞品",
                "description": "discover",
                "competitor_id": None,
                "focus_dimensions": ["feature", "pricing"],
            },
            {
                "stage": "research",
                "title": "调研 Cursor",
                "description": "research",
                "competitor_id": "Cursor",
                "focus_dimensions": ["feature", "pricing"],
            },
            {
                "stage": "analyze",
                "title": "机会地图",
                "description": "analyze",
                "competitor_id": None,
                "focus_dimensions": ["feature", "pricing"],
            },
            {
                "stage": "analyze",
                "title": "趋势与格局",
                "description": "analyze",
                "competitor_id": None,
                "focus_dimensions": ["feature", "pricing"],
            },
            {
                "stage": "write",
                "title": "撰写",
                "description": "write",
                "competitor_id": None,
                "focus_dimensions": ["feature", "pricing"],
            },
        ],
    }
    parsed = PlannerOutput.parse_llm_content(
        content,
        draft=RunIntakeDraft(
            user_query="AI coding 工具赛道机会",
            analysis_intent="AI coding 工具赛道机会",
            analysis_archetype="landscape",
            competitors_discovery_mode=True,
        ),
    )

    assert [task.stage for task in parsed.tasks].count("analyze") == 2


def test_route_after_qa_rejected_goes_replanner() -> None:
    assert _route_after_qa({"qa_outcome": "rejected"}) == "replanner"
    assert _route_after_qa({"qa_outcome": "approved"}) == "deepen"
    assert _route_after_qa({"qa_outcome": "force_degraded"}) == "supervisor"
