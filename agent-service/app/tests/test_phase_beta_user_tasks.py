"""Phase β user-injected task tests.

Unit-tests the two new helpers (`_normalize_user_tasks`,
`_extract_user_pinned_research`) and exercises the full e2e where the user
adds a research task via /plan/confirm and the supervisor picks the
pinned competitor before its own discovery candidates.
"""

from __future__ import annotations

import time

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from agents.nodes.planner import (
    _cap_plan_tasks_for_profile,
    _merge_plan_tasks_with_user_priority,
    _normalize_user_tasks,
)
from agents.nodes.supervisor import _extract_user_pinned_research
from core.config import settings
from schemas.plan import PlanTask


# ---- _normalize_user_tasks ---------------------------------------------------


def test_normalize_user_tasks_forces_source_priority_and_new_id() -> None:
    raw = PlanTask(
        task_id="ptask_client_supplied",
        stage="research",
        title="  Extra competitor research  ",
        description="  some context  ",
        competitor_id="GitHub Copilot",
        focus_dimensions=["pricing"],
        source="agent",
        priority="normal",
        enabled=False,
    )
    [normalized] = _normalize_user_tasks([raw])
    assert normalized.task_id.startswith("ptask_")
    assert normalized.task_id != "ptask_client_supplied"
    assert normalized.source == "user"
    assert normalized.priority == "user_pinned"
    assert normalized.enabled is True
    assert normalized.title == "Extra competitor research"
    assert normalized.description == "some context"
    assert normalized.competitor_id == "GitHub Copilot"
    assert normalized.focus_dimensions == ["pricing"]


def test_normalize_user_tasks_rejects_discover_stage() -> None:
    with pytest.raises(ValueError, match="user-addable"):
        _normalize_user_tasks(
            [PlanTask(stage="discover", title="find more", competitor_id=None)]
        )


def test_normalize_user_tasks_rejects_research_without_competitor_id() -> None:
    with pytest.raises(ValueError, match="competitor_id is required"):
        _normalize_user_tasks(
            [PlanTask(stage="research", title="research nobody", competitor_id=None)]
        )


def test_normalize_user_tasks_rejects_empty_title() -> None:
    with pytest.raises(ValueError, match="title must be non-empty"):
        _normalize_user_tasks(
            [PlanTask(stage="analyze", title="   ", competitor_id=None)]
        )


def test_normalize_user_tasks_truncates_long_title_and_description() -> None:
    raw = PlanTask(
        stage="analyze",
        title="x" * 200,
        description="y" * 1000,
        competitor_id=None,
    )
    [normalized] = _normalize_user_tasks([raw])
    assert len(normalized.title) == 60
    assert len(normalized.description) == 500


def test_normalize_user_tasks_drops_competitor_id_for_non_research_stages() -> None:
    raw = PlanTask(
        stage="write",
        title="extra section",
        competitor_id="ShouldNotPersist",
    )
    [normalized] = _normalize_user_tasks([raw])
    assert normalized.competitor_id is None


def test_merge_plan_tasks_prioritizes_user_tasks_under_research_cap() -> None:
    kept_tasks = [
        PlanTask(stage="discover", title="discover"),
        PlanTask(stage="research", title="agent_a", competitor_id="AgentA", source="agent"),
        PlanTask(stage="research", title="agent_b", competitor_id="AgentB", source="agent"),
        PlanTask(stage="analyze", title="analyze"),
        PlanTask(stage="write", title="write"),
    ]
    user_tasks = [
        PlanTask(
            stage="research",
            title="user_pinned",
            competitor_id="UserPinned",
            source="user",
            priority="user_pinned",
        )
    ]

    merged_candidates = _merge_plan_tasks_with_user_priority(
        kept_tasks=kept_tasks,
        user_tasks=user_tasks,
    )
    capped = _cap_plan_tasks_for_profile(
        merged_candidates,
        analysis_archetype="comparison",
        max_competitors=2,
        max_dimensions=3,
    )

    research_competitors = [
        task.competitor_id
        for task in capped
        if task.stage == "research" and task.competitor_id is not None
    ]
    assert "UserPinned" in research_competitors
    assert len(research_competitors) == 2


# ---- _extract_user_pinned_research -------------------------------------------


def test_extract_user_pinned_research_filters_correctly() -> None:
    plan_tree = {
        "tasks": [
            # included: user + research + pinned + not researched
            {
                "task_id": "ptask_a",
                "stage": "research",
                "title": "Cover Anthropic",
                "competitor_id": "Anthropic",
                "focus_dimensions": ["pricing"],
                "source": "user",
                "priority": "user_pinned",
                "enabled": True,
            },
            # excluded: not user
            {
                "stage": "research",
                "title": "agent task",
                "competitor_id": "Notion",
                "source": "agent",
                "priority": "normal",
            },
            # excluded: not research
            {
                "stage": "analyze",
                "title": "extra section",
                "source": "user",
                "priority": "user_pinned",
            },
            # excluded: already researched
            {
                "stage": "research",
                "title": "done already",
                "competitor_id": "Cursor",
                "source": "user",
                "priority": "user_pinned",
            },
            # excluded: enabled=False
            {
                "stage": "research",
                "title": "dropped",
                "competitor_id": "Linear",
                "source": "user",
                "priority": "user_pinned",
                "enabled": False,
            },
        ]
    }
    result = _extract_user_pinned_research(
        plan_tree=plan_tree,
        researched_competitors=["Cursor"],
    )
    assert len(result) == 1
    entry = result[0]
    assert entry["competitor_id"] == "Anthropic"
    assert entry["title"] == "Cover Anthropic"
    assert entry["focus_dimensions"] == ["pricing"]


def test_extract_user_pinned_research_empty_for_no_plan_tree() -> None:
    assert _extract_user_pinned_research(plan_tree=None, researched_competitors=[]) == []
    assert _extract_user_pinned_research(plan_tree={"tasks": []}, researched_competitors=[]) == []


# ---- e2e: /plan/confirm with additional_tasks --------------------------------


def _wait_for_run_status(
    run_id: str,
    expected_statuses: set[str],
    *,
    timeout_seconds: float = 60.0,
) -> str:
    deadline = time.time() + timeout_seconds
    last_status = "running"
    while time.time() < deadline:
        engine = create_engine(settings.DATABASE_URL_SYNC)
        try:
            with engine.connect() as connection:
                row = connection.execute(
                    text("SELECT status FROM runs WHERE run_id = :run_id"),
                    {"run_id": run_id},
                ).mappings().first()
        finally:
            engine.dispose()
        if row is not None:
            last_status = str(row["status"])
            if last_status in expected_statuses:
                return last_status
        time.sleep(0.1)
    return last_status


def _wait_for_intake_field(
    test_client: TestClient, run_id: str, field: str, *, timeout_seconds: float = 10.0
) -> None:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        detail = test_client.get(f"/api/runs/{run_id}").json() or {}
        draft = detail.get("intake_draft") or {}
        if draft.get(field):
            return
        time.sleep(0.1)
    pytest.fail(f"intake_draft.{field} never set within {timeout_seconds}s")


def _post_intake_reply_when_ready(
    test_client: TestClient,
    *,
    run_id: str,
    body: dict[str, object],
    timeout_seconds: float = 10.0,
) -> None:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        response = test_client.post(f"/api/runs/{run_id}/intake/reply", json=body)
        if response.status_code == 200:
            return
        if response.status_code == 409 and response.json().get("error_code") == "INTAKE_NOT_AWAITING_REPLY":
            time.sleep(0.1)
            continue
        pytest.fail(f"unexpected intake reply response: {response.status_code} {response.text}")
    pytest.fail("intake/reply never became resumable within timeout")


def _drive_intake_to_planner_pause(test_client: TestClient) -> str:
    create = test_client.post(
        "/api/runs/intake",
        json={"user_query": "对比 Notion 和 Cursor 的定价策略"},
    )
    assert create.status_code == 200, create.text
    run_id = create.json()["run_id"]

    _post_intake_reply_when_ready(
        test_client,
        run_id=run_id,
        body={"text": "pm", "selected_options": ["pm"]},
    )
    _wait_for_intake_field(test_client, run_id, "user_role")

    _post_intake_reply_when_ready(
        test_client,
        run_id=run_id,
        body={"text": "对比 Notion 和 Cursor 的定价策略"},
    )
    _wait_for_intake_field(test_client, run_id, "analysis_intent")

    _post_intake_reply_when_ready(
        test_client,
        run_id=run_id,
        body={"text": "Notion, Cursor", "selected_options": ["已有名单"]},
    )

    # Intake complete: the graph pauses at the planning-profile depth gate;
    # one more reply with the depth choice resumes into planner_generate.
    deadline = time.time() + 30.0
    while time.time() < deadline:
        detail = test_client.get(f"/api/runs/{run_id}").json()
        if detail.get("phase") == "planning" and detail.get("plan_tree") is None:
            break
        time.sleep(0.1)
    else:
        pytest.fail("run never entered planning gate within 30s")

    _post_intake_reply_when_ready(
        test_client,
        run_id=run_id,
        body={"text": "", "selected_options": ["quick"]},
    )

    deadline = time.time() + 30.0
    while time.time() < deadline:
        detail = test_client.get(f"/api/runs/{run_id}").json()
        candidate = detail.get("plan_tree")
        if isinstance(candidate, dict) and candidate.get("confirmed_at") is None:
            return run_id
        time.sleep(0.1)
    pytest.fail("plan_tree never published within 30s")


def test_plan_confirm_merges_additional_user_tasks(test_client: TestClient) -> None:
    """A user-injected research task for a NEW competitor must:
      1. land in the confirmed plan tree as source=user / priority=user_pinned;
      2. cause the supervisor to research the user-pinned competitor too;
      3. finish the run in a terminal state.
    """
    run_id = _drive_intake_to_planner_pause(test_client)

    confirm = test_client.post(
        f"/api/runs/{run_id}/plan/confirm",
        json={
            "disabled_task_ids": [],
            "additional_tasks": [
                {
                    "stage": "research",
                    "title": "调研 GitHub Copilot 的定价",
                    "description": "用户加入的额外竞品。",
                    "competitor_id": "GitHub Copilot",
                    "focus_dimensions": ["pricing"],
                    # Client-supplied source / priority must be overridden by server.
                    "source": "agent",
                    "priority": "normal",
                }
            ],
        },
    )
    assert confirm.status_code == 200, confirm.text

    final_status = _wait_for_run_status(
        run_id,
        expected_statuses={"completed", "degraded", "failed"},
        timeout_seconds=60.0,
    )
    assert final_status in {"completed", "degraded"}, (
        f"expected terminal success, got {final_status}"
    )

    detail = test_client.get(f"/api/runs/{run_id}").json()
    confirmed_plan = detail["plan_tree"]
    assert confirmed_plan is not None
    assert confirmed_plan["version"] == 2

    user_tasks = [task for task in confirmed_plan["tasks"] if task.get("source") == "user"]
    assert len(user_tasks) == 1
    pinned = user_tasks[0]
    assert pinned["stage"] == "research"
    assert pinned["competitor_id"] == "GitHub Copilot"
    assert pinned["priority"] == "user_pinned"
    assert pinned["enabled"] is True
    assert pinned["title"] == "调研 GitHub Copilot 的定价"
    # FE-supplied task_id must be replaced with a fresh ptask_ prefix.
    assert pinned["task_id"].startswith("ptask_")

    # The user-pinned competitor must show up in Run.competitors so analyst /
    # writer downstream can reach it without re-running discovery.
    persisted_competitors = detail.get("competitors") or []
    assert "GitHub Copilot" in persisted_competitors


def test_plan_confirm_rejects_disabled_id_not_in_pending_plan(
    test_client: TestClient,
) -> None:
    """`disabled_task_ids` referencing tasks that don't exist in the pending
    plan must fail loudly so the FE detects a stale plan version, not
    silently confirm with a no-op disable.
    """
    run_id = _drive_intake_to_planner_pause(test_client)

    confirm = test_client.post(
        f"/api/runs/{run_id}/plan/confirm",
        json={
            "disabled_task_ids": ["ptask_does_not_exist"],
            "additional_tasks": [],
        },
    )
    # The endpoint returns 200 (accept), but the background task fails when
    # planner_wait validates the resume payload — surfaced as Run.status="failed".
    assert confirm.status_code == 200, confirm.text
    final_status = _wait_for_run_status(
        run_id,
        expected_statuses={"completed", "degraded", "failed"},
        timeout_seconds=30.0,
    )
    assert final_status == "failed"


def test_plan_confirm_rejects_user_injected_discover_stage(
    test_client: TestClient,
) -> None:
    """`stage="discover"` is reserved for the Agent's discovery node; user
    injection must be refused (background task fails).
    """
    run_id = _drive_intake_to_planner_pause(test_client)

    confirm = test_client.post(
        f"/api/runs/{run_id}/plan/confirm",
        json={
            "disabled_task_ids": [],
            "additional_tasks": [
                {
                    "stage": "discover",
                    "title": "discover more",
                    "competitor_id": None,
                }
            ],
        },
    )
    assert confirm.status_code == 200, confirm.text
    final_status = _wait_for_run_status(
        run_id,
        expected_statuses={"completed", "degraded", "failed"},
        timeout_seconds=30.0,
    )
    assert final_status == "failed"
