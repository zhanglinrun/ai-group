from __future__ import annotations

import json

import pytest

from agents.nodes.planner import reconcile_plan_tree_after_discovery
from core.defaults import MAX_DISCOVERY_COMPETITORS, MAX_RESEARCH_COMPETITORS
from schemas.contracts import COMPARISON_SCHEMA_BASE_DIMENSIONS
from schemas.plan import PlanTask, PlanTree
from utils.logger import configure_logging


def test_reconcile_plan_tree_inserts_research_tasks_after_discover() -> None:
    plan = PlanTree(
        plan_id="plan_test",
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(stage="analyze", title="分析", description="analyze"),
            PlanTask(stage="write", title="撰写", description="write"),
        ],
        version=1,
    )
    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=["Notion", "Cursor"],
        focus_dimensions=["feature", "pricing"],
    )
    stages = [task.stage for task in reconciled.tasks]
    assert stages == ["discover", "research", "research", "analyze", "write"]
    research_tasks = [task for task in reconciled.tasks if task.stage == "research"]
    assert [task.competitor_id for task in research_tasks] == ["Notion", "Cursor"]
    assert reconciled.version == 2


def test_plan_task_normalizes_focus_dimensions_contract_ids() -> None:
    task = PlanTask(
        stage="analyze",
        title="分析",
        description="analyze",
        focus_dimensions=["产品定位", "Pricing Strategy", "china_vs_global"],
    )

    assert task.focus_dimensions == ["pricing_strategy", "market_differences"]


def test_reconcile_plan_tree_skips_duplicate_competitors() -> None:
    plan = PlanTree(
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(
                stage="research",
                title="调研 Notion",
                description="research",
                competitor_id="Notion",
            ),
            PlanTask(stage="analyze", title="分析", description="analyze"),
        ],
        version=3,
    )
    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=["Notion", "Cursor"],
    )
    research_competitors = [
        task.competitor_id for task in reconciled.tasks if task.stage == "research"
    ]
    assert research_competitors == ["Notion", "Cursor"]
    assert reconciled.version == 4


def test_reconcile_plan_tree_preserves_candidate_role_in_sources_and_description() -> None:
    plan = PlanTree(
        plan_id="plan_test",
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(stage="analyze", title="分析", description="analyze"),
        ],
        version=1,
    )
    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=["Meta Ray-Ban"],
        discovered_competitor_sources={
            "Meta Ray-Ban": {
                "official_url": None,
                "source_domain": None,
                "candidate_role": "direct_competitor",
                "relevance_reason": "AI 眼镜核心竞争样本。",
            }
        },
    )

    research_tasks = [task for task in reconciled.tasks if task.stage == "research"]
    assert len(research_tasks) == 1
    assert "候选角色：核心竞争样本" in research_tasks[0].description
    assert reconciled.competitor_sources["Meta Ray-Ban"] == {
        "official_url": None,
        "source_domain": None,
        "candidate_role": "direct_competitor",
        "relevance_reason": "AI 眼镜核心竞争样本。",
        "segment": None,
        "introduction": None,
        "vendor": None,
    }


def test_reconcile_plan_tree_landscape_caps_core_deepdive_and_prefers_peripheral_fill() -> None:
    plan = PlanTree(
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(stage="analyze", title="分析", description="analyze"),
        ],
        version=1,
    )
    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=[
            "Meta Ray-Ban",
            "XREAL",
            "NVIDIA",
            "CAICT",
        ],
        discovered_competitor_sources={
            "Meta Ray-Ban": {"candidate_role": "direct_competitor"},
            "XREAL": {"candidate_role": "adjacent_competitor"},
            "NVIDIA": {"candidate_role": "upstream_supplier"},
            "CAICT": {"candidate_role": "trend_reference"},
        },
        analysis_archetype="landscape",
        max_competitors=3,
        landscape_core_deepdive_n=1,
    )

    research_competitors = [
        task.competitor_id for task in reconciled.tasks if task.stage == "research"
    ]
    assert research_competitors == ["Meta Ray-Ban", "NVIDIA", "CAICT"]


def test_reconcile_plan_tree_landscape_core_tasks_keep_schema_dimensions() -> None:
    plan = PlanTree(
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(stage="analyze", title="分析", description="analyze"),
        ],
        version=1,
    )
    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=["Meta Ray-Ban", "XREAL", "NVIDIA"],
        discovered_competitor_sources={
            "Meta Ray-Ban": {"candidate_role": "direct_competitor"},
            "XREAL": {"candidate_role": "adjacent_competitor"},
            "NVIDIA": {"candidate_role": "upstream_supplier"},
        },
        focus_dimensions=["market_differences", "product_positioning"],
        analysis_archetype="landscape",
        max_competitors=3,
        max_dimensions=3,
        landscape_core_deepdive_n=2,
    )

    research_by_competitor = {
        task.competitor_id: task
        for task in reconciled.tasks
        if task.stage == "research" and isinstance(task.competitor_id, str)
    }
    for core_competitor in ("Meta Ray-Ban", "XREAL"):
        assert research_by_competitor[core_competitor].focus_dimensions[:3] == list(
            COMPARISON_SCHEMA_BASE_DIMENSIONS
        )
    peripheral_dimensions = research_by_competitor["NVIDIA"].focus_dimensions
    assert "feature" not in peripheral_dimensions
    assert "pricing" not in peripheral_dimensions
    assert "user_feedback" not in peripheral_dimensions


def test_reconcile_plan_tree_landscape_backfills_core_deepdive_when_all_non_core() -> None:
    plan = PlanTree(
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(stage="analyze", title="分析", description="analyze"),
        ],
        version=1,
    )
    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=["Meta Ray-Ban", "IDC", "NVIDIA", "Counterpoint"],
        discovered_competitor_sources={
            "Meta Ray-Ban": {"candidate_role": "trend_reference"},
            "IDC": {"candidate_role": "trend_reference"},
            "NVIDIA": {"candidate_role": "upstream_supplier"},
            "Counterpoint": {"candidate_role": "trend_reference"},
        },
        analysis_archetype="landscape",
        max_competitors=3,
        max_dimensions=3,
        landscape_core_deepdive_n=2,
    )

    research_tasks = [task for task in reconciled.tasks if task.stage == "research"]
    assert [task.competitor_id for task in research_tasks] == ["Meta Ray-Ban", "IDC", "NVIDIA"]
    for task in research_tasks[:2]:
        assert task.focus_dimensions[:3] == list(COMPARISON_SCHEMA_BASE_DIMENSIONS)
    assert "feature" not in (research_tasks[2].focus_dimensions or [])
    assert "pricing" not in (research_tasks[2].focus_dimensions or [])
    assert "user_feedback" not in (research_tasks[2].focus_dimensions or [])


def test_reconcile_plan_tree_logs_authoritative_research_competitor_set(
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging()
    plan = PlanTree(
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(stage="analyze", title="分析", description="analyze"),
        ],
        version=1,
    )
    competitors = [f"Competitor {index}" for index in range(MAX_DISCOVERY_COMPETITORS)]

    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=competitors,
    )

    research_competitors = [
        task.competitor_id for task in reconciled.tasks if task.stage == "research"
    ]
    assert research_competitors == competitors[:MAX_RESEARCH_COMPETITORS]
    logged_lines = capsys.readouterr().out.splitlines()
    assert not any("planner.reconcile.discovery_capped" in line for line in logged_lines)
    logged = [
        json.loads(line)
        for line in logged_lines
        if "planner.reconcile.research_competitor_set" in line
    ]
    assert logged
    assert logged[-1]["cap"] == MAX_RESEARCH_COMPETITORS
    assert logged[-1]["discovered_count"] == MAX_DISCOVERY_COMPETITORS
    assert logged[-1]["existing_research_count"] == 0
    assert logged[-1]["new_research_count"] == MAX_RESEARCH_COMPETITORS
    assert logged[-1]["actual_research_count"] == MAX_RESEARCH_COMPETITORS
    assert logged[-1]["actual_research_competitors"] == research_competitors
    assert logged[-1]["dropped_competitors"] == competitors[MAX_RESEARCH_COMPETITORS:]
    assert logged[-1]["capped"] is True


def test_reconcile_plan_tree_logs_actual_research_set_with_existing_tasks(
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging()
    plan = PlanTree(
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(
                stage="research",
                title="调研 Existing 8",
                description="research",
                competitor_id="Competitor 8",
            ),
            PlanTask(stage="analyze", title="分析", description="analyze"),
        ],
        version=1,
    )
    competitors = [f"Competitor {index}" for index in range(MAX_DISCOVERY_COMPETITORS)]

    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=competitors,
    )

    research_competitors = [
        task.competitor_id for task in reconciled.tasks if task.stage == "research"
    ]
    logged = [
        json.loads(line)
        for line in capsys.readouterr().out.splitlines()
        if "planner.reconcile.research_competitor_set" in line
    ]
    assert logged
    assert logged[-1]["existing_research_count"] == 1
    assert logged[-1]["new_research_count"] == MAX_RESEARCH_COMPETITORS
    assert logged[-1]["actual_research_competitors"] == research_competitors
    assert "Competitor 8" in logged[-1]["actual_research_competitors"]
    assert "Competitor 8" not in logged[-1]["dropped_competitors"]


def test_reconcile_plan_tree_allows_existing_competitors_from_state() -> None:
    plan = PlanTree(
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(
                stage="research",
                title="调研 Cursor",
                description="research",
                competitor_id="Cursor",
            ),
            PlanTask(stage="analyze", title="分析", description="analyze"),
        ],
        version=1,
    )
    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=["Windsurf"],
        existing_competitors=["Cursor"],
    )
    research_competitors = [
        task.competitor_id for task in reconciled.tasks if task.stage == "research"
    ]
    assert set(research_competitors) == {"Cursor", "Windsurf"}


def test_reconcile_plan_tree_preserves_existing_research_not_in_discovery() -> None:
    plan = PlanTree(
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(
                stage="research",
                title="调研 beisen",
                description="research",
                competitor_id="beisen",
            ),
            PlanTask(stage="analyze", title="分析", description="analyze"),
        ],
        version=1,
    )
    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=["北森", "Moka"],
        existing_competitors=[],
    )

    research_competitors = [
        task.competitor_id for task in reconciled.tasks if task.stage == "research"
    ]
    assert research_competitors == ["beisen", "北森", "Moka"]

