"""Phase 2 plan-confirm API tests: end-to-end through FastAPI TestClient.

Pairs with test_plan_flow.py (graph-level invariants) and test_intake_api.py
(intake-stage assertions). Drives the full chat-mode lifecycle:
intake → planner pauses → /plan/confirm → executor → terminal status.
"""

from __future__ import annotations

import time

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from core.config import settings


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
    """Poll the run detail until intake_draft[field] is truthy.

    The reply endpoint returns 202 immediately and the graph resumes on a
    background task; without this wait the next reply races the resume and
    fires while next_node=("intake_generate",) → 409 INTAKE_NOT_AWAITING_REPLY.
    """
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


def _drive_intake_to_planner_pause(
    test_client: TestClient, *, user_query: str = "我想分析定价竞品"
) -> tuple[str, dict[str, object]]:
    """Helper: drive a fresh run through intake until the planner has published a plan.

    Returns (run_id, plan_tree). The run is left paused at planner_wait, ready
    for /plan/confirm.
    """
    create = test_client.post("/api/runs/intake", json={"user_query": user_query})
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

    deadline = time.time() + 30.0
    plan_tree: dict[str, object] | None = None
    while time.time() < deadline:
        detail = test_client.get(f"/api/runs/{run_id}").json()
        candidate = detail.get("plan_tree")
        if isinstance(candidate, dict) and candidate.get("confirmed_at") is None:
            plan_tree = candidate
            break
        time.sleep(0.1)
    if plan_tree is None:
        pytest.fail("planner_wait never reached: plan_tree did not appear within 30s")
    return run_id, plan_tree


def test_plan_confirm_drives_run_to_terminal(test_client: TestClient) -> None:
    run_id, plan = _drive_intake_to_planner_pause(test_client)

    cursor_research = next(
        (
            task
            for task in plan["tasks"]  # type: ignore[index]
            if task.get("stage") == "research" and task.get("competitor_id") == "Cursor"
        ),
        None,
    )
    assert cursor_research is not None, "fake planner must emit a research task for Cursor"

    confirm = test_client.post(
        f"/api/runs/{run_id}/plan/confirm",
        json={"disabled_task_ids": [cursor_research["task_id"]], "additional_tasks": []},
    )
    assert confirm.status_code == 200, confirm.text
    body = confirm.json()
    assert body["run_id"] == run_id
    assert body["status"] == "running"

    final_status = _wait_for_run_status(
        run_id,
        expected_statuses={"completed", "degraded", "failed"},
        timeout_seconds=60.0,
    )
    assert final_status in {"completed", "degraded"}, (
        f"expected terminal success, got {final_status}"
    )

    detail = test_client.get(f"/api/runs/{run_id}").json()
    assert detail["phase"] == "done"
    confirmed_plan = detail["plan_tree"]
    assert confirmed_plan is not None
    assert confirmed_plan["confirmed_at"] is not None
    assert confirmed_plan["version"] == 2
    stages = [task["stage"] for task in confirmed_plan["tasks"]]
    # Cursor research dropped; Notion research kept; analyze + write retained.
    assert stages.count("research") == 1
    assert all(task["task_id"] != cursor_research["task_id"] for task in confirmed_plan["tasks"])


def test_plan_confirm_rejects_when_not_paused(test_client: TestClient) -> None:
    """Confirming on a run that is still in intake stage must 409.

    Guards against the FE accidentally posting /plan/confirm before the planner
    pauses (e.g., the user reaches the plan page during an intake turn).
    """
    create = test_client.post("/api/runs/intake", json={"user_query": "尚未澄清完毕"})
    assert create.status_code == 200
    run_id = create.json()["run_id"]

    confirm = test_client.post(
        f"/api/runs/{run_id}/plan/confirm",
        json={"disabled_task_ids": [], "additional_tasks": []},
    )
    assert confirm.status_code == 409
    body = confirm.json()
    assert body["error_code"] == "PLAN_NOT_AWAITING_CONFIRM"


def test_plan_confirm_404_on_nonexistent_run(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs/run_does_not_exist/plan/confirm",
        json={"disabled_task_ids": [], "additional_tasks": []},
    )
    assert response.status_code == 404
    assert response.json()["error_code"] == "RUN_NOT_FOUND"
