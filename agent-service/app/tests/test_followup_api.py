"""Phase 4 follow-up API tests + supervisor consumption.

Strategy: separate the endpoint guards (covered with hand-built Run rows so
we don't depend on the live graph state) from the e2e GRAPH_PAUSED case
(driven through the real intake flow). Supervisor consumption is verified
with direct DB asserts on `runs.follow_ups[*].consumed_at`.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
import time
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from core.config import settings
from service.llm.prompts import _format_pending_follow_ups


# ---- prompt helper (pure) ----------------------------------------------------


def test_format_pending_follow_ups_empty_returns_blank() -> None:
    assert _format_pending_follow_ups(None) == ""
    assert _format_pending_follow_ups([]) == ""
    # Garbage entries are silently filtered (no `text` field).
    assert _format_pending_follow_ups([{"foo": "bar"}, "not_a_dict"]) == ""  # type: ignore[list-item]


def test_format_pending_follow_ups_renders_entries_with_id_and_stage() -> None:
    rendered = _format_pending_follow_ups(
        [
            {"id": "fu_a", "text": "look at github copilot", "applies_to_stage": "research"},
            {"id": "fu_b", "text": "skip security analysis", "applies_to_stage": None},
            {"id": "fu_c", "text": "  trimmed  "},
        ]
    )
    assert "User mid-run instructions" in rendered
    assert "fu_a [research] look at github copilot" in rendered
    assert "fu_b skip security analysis" in rendered
    assert "fu_c trimmed" in rendered
    # ensure leading/trailing whitespace stripped on entry text
    assert "  trimmed  " not in rendered


# ---- endpoint guards ---------------------------------------------------------


def _make_run_id() -> str:
    return f"run_{uuid.uuid4().hex[:12]}"


def _insert_run_row(
    *,
    run_id: str,
    status: str,
    plan_tree: dict[str, object] | None,
) -> None:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    plan_tree_text = json.dumps(plan_tree) if plan_tree is not None else None
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO runs ("
                    "  run_id, user_query, status, target_roles, competitors,"
                    "  started_at, created_at, plan_tree"
                    ") VALUES ("
                    "  :run_id, :user_query, :status,"
                    "  CAST(:target_roles AS jsonb),"
                    "  CAST(:competitors AS jsonb),"
                    "  :started_at, :created_at,"
                    "  CAST(:plan_tree AS jsonb)"
                    ")"
                ),
                {
                    "run_id": run_id,
                    "user_query": "phase4 endpoint guard",
                    "status": status,
                    "target_roles": "[]",
                    "competitors": "[]",
                    "started_at": datetime.now(timezone.utc),
                    "created_at": datetime.now(timezone.utc),
                    "plan_tree": plan_tree_text,
                },
            )
    finally:
        engine.dispose()


def _fetch_follow_ups(run_id: str) -> list[dict[str, object]] | None:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.connect() as connection:
            row = connection.execute(
                text("SELECT follow_ups FROM runs WHERE run_id = :run_id"),
                {"run_id": run_id},
            ).mappings().first()
    finally:
        engine.dispose()
    if row is None:
        return None
    value = row["follow_ups"]
    if value is None:
        return None
    assert isinstance(value, list)
    return value


def test_followup_404_on_nonexistent_run(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs/run_phase4_missing/follow-up",
        json={"text": "anything"},
    )
    assert response.status_code == 404
    assert response.json()["error_code"] == "RUN_NOT_FOUND"


def test_followup_409_when_run_not_running(test_client: TestClient) -> None:
    run_id = _make_run_id()
    plan_tree = {
        "plan_id": "plan_x",
        "tasks": [],
        "rationale": "",
        "version": 2,
        "confirmed_at": "2026-05-31T00:00:00+00:00",
    }
    _insert_run_row(run_id=run_id, status="completed", plan_tree=plan_tree)

    response = test_client.post(
        f"/api/runs/{run_id}/follow-up",
        json={"text": "too late"},
    )
    assert response.status_code == 409
    assert response.json()["error_code"] == "FOLLOWUP_RUN_NOT_RUNNING"


def test_followup_409_when_plan_not_confirmed(test_client: TestClient) -> None:
    run_id = _make_run_id()
    # plan_tree=None (still in planning / intake)
    _insert_run_row(run_id=run_id, status="running", plan_tree=None)

    response = test_client.post(
        f"/api/runs/{run_id}/follow-up",
        json={"text": "too early"},
    )
    assert response.status_code == 409
    assert response.json()["error_code"] == "FOLLOWUP_NOT_EXECUTING"


def test_followup_422_on_empty_text(test_client: TestClient) -> None:
    run_id = _make_run_id()
    plan_tree = {
        "plan_id": "plan_y",
        "tasks": [],
        "rationale": "",
        "version": 2,
        "confirmed_at": "2026-05-31T00:00:00+00:00",
    }
    _insert_run_row(run_id=run_id, status="running", plan_tree=plan_tree)

    response = test_client.post(
        f"/api/runs/{run_id}/follow-up",
        json={"text": ""},
    )
    assert response.status_code == 422


def test_followup_happy_path_persists_entry(test_client: TestClient) -> None:
    """Running run + confirmed plan + graph thread without checkpoint =
    snapshot.next is the empty tuple, which is NOT in the paused set, so the
    endpoint accepts the addendum. Validates persistence shape end-to-end.
    """
    run_id = _make_run_id()
    plan_tree = {
        "plan_id": "plan_z",
        "tasks": [],
        "rationale": "",
        "version": 2,
        "confirmed_at": "2026-05-31T00:00:00+00:00",
    }
    _insert_run_row(run_id=run_id, status="running", plan_tree=plan_tree)

    response = test_client.post(
        f"/api/runs/{run_id}/follow-up",
        json={
            "text": "再多找 GitHub Copilot 的 pricing 资料",
            "applies_to_stage": "research",
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["run_id"] == run_id
    assert body["follow_up_id"].startswith("fu_")
    assert body["received_at"]

    # A second submission stacks on top of the first instead of replacing it.
    second = test_client.post(
        f"/api/runs/{run_id}/follow-up",
        json={"text": "顺便看看安全审计"},
    )
    assert second.status_code == 200

    rows = _fetch_follow_ups(run_id)
    assert rows is not None
    assert len(rows) == 2
    assert rows[0]["id"] == body["follow_up_id"]
    assert rows[0]["text"] == "再多找 GitHub Copilot 的 pricing 资料"
    assert rows[0]["applies_to_stage"] == "research"
    assert rows[0]["consumed_at"] is None
    assert rows[1]["text"] == "顺便看看安全审计"
    assert rows[1]["applies_to_stage"] is None
    assert rows[1]["consumed_at"] is None


# ---- e2e: GRAPH_PAUSED when at intake_wait ----------------------------------


def test_followup_409_when_paused_at_intake_wait(test_client: TestClient) -> None:
    create = test_client.post(
        "/api/runs/intake",
        json={"user_query": "phase 4 follow-up paused-intake check"},
    )
    assert create.status_code == 200, create.text
    run_id = create.json()["run_id"]

    # Without a plan_tree the run is in the planning phase (intake gate). The
    # endpoint must reject with NOT_EXECUTING before even probing the graph.
    response = test_client.post(
        f"/api/runs/{run_id}/follow-up",
        json={"text": "halt"},
    )
    assert response.status_code == 409
    assert response.json()["error_code"] == "FOLLOWUP_NOT_EXECUTING"


# ---- e2e: supervisor consumes follow-up + marks consumed --------------------


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


def _seed_follow_up_directly(
    *,
    run_id: str,
    text_payload: str,
    received_at: str,
) -> str:
    """Insert a pending follow-up *before* /plan/confirm so it's guaranteed to
    be picked up by the very first supervisor iteration. Bypasses the endpoint
    (which would reject NOT_EXECUTING during planning) — this is a white-box
    setup specifically for the consumption test.
    """
    follow_up_id = f"fu_{uuid.uuid4().hex[:12]}"
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE runs SET follow_ups = jsonb_build_array("
                    "  jsonb_build_object("
                    "    'id', :fu_id,"
                    "    'text', :text,"
                    "    'applies_to_stage', NULL,"
                    "    'received_at', :received_at,"
                    "    'consumed_at', NULL,"
                    "    'consumed_in_iteration', NULL"
                    "  )"
                    ") WHERE run_id = :run_id"
                ),
                {
                    "fu_id": follow_up_id,
                    "text": text_payload,
                    "received_at": received_at,
                    "run_id": run_id,
                },
            )
    finally:
        engine.dispose()
    return follow_up_id


def test_supervisor_consumes_pending_follow_up_during_run(test_client: TestClient) -> None:
    """Seed a follow-up into Run.follow_ups before the executor starts; after
    the run terminates the entry must be marked consumed with an iteration
    stamp, proving the supervisor saw + processed it.
    """
    create = test_client.post(
        "/api/runs/intake",
        json={"user_query": "对比 Notion 和 Cursor 的定价"},
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

    # Intake complete: the graph pauses at the planning-profile depth gate.
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

    # Wait for the planner to publish so the run is paused at planner_wait
    deadline = time.time() + 30.0
    while time.time() < deadline:
        detail = test_client.get(f"/api/runs/{run_id}").json()
        if isinstance(detail.get("plan_tree"), dict):
            break
        time.sleep(0.1)
    else:
        pytest.fail("plan_tree never published within 30s")

    received_at = datetime.now(timezone.utc).isoformat()
    seeded_id = _seed_follow_up_directly(
        run_id=run_id,
        text_payload="再多看 GitHub Copilot 的 pricing tier",
        received_at=received_at,
    )

    confirm = test_client.post(
        f"/api/runs/{run_id}/plan/confirm",
        json={"disabled_task_ids": [], "additional_tasks": []},
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

    rows = _fetch_follow_ups(run_id)
    assert rows is not None and len(rows) == 1
    consumed_row = rows[0]
    assert consumed_row["id"] == seeded_id
    assert consumed_row["consumed_at"] is not None
    consumed_in_iteration = consumed_row["consumed_in_iteration"]
    assert isinstance(consumed_in_iteration, int) and consumed_in_iteration >= 1
