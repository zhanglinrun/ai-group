"""Phase 1b intake API tests: end-to-end through FastAPI TestClient.

Exercises the full request path (lifespan → compile_graph → AsyncPostgresSaver),
which catches integration regressions that the unit-level test_intake_flow.py
cannot — e.g., wrong field names in the response model, missing imports, broken
background-task wiring, or stale stub raisers.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from core.config import settings


def _wait_for_run_status(
    run_id: str,
    expected_statuses: set[str],
    *,
    timeout_seconds: float = 30.0,
) -> str:
    """Poll the runs table until status enters `expected_statuses` (or timeout)."""
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


def _post_intake_reply_when_ready(
    test_client: TestClient,
    *,
    run_id: str,
    body: dict[str, object],
    timeout_seconds: float = 10.0,
) -> None:
    """Retry intake/reply until graph is paused at a reply-compatible gate."""
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


def test_intake_create_returns_accepted(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs/intake",
        json={"user_query": "我想分析定价竞品"},
    )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["status"] == "running"
    assert payload["phase"] == "intake"
    assert payload["run_id"].startswith("run_")
    assert payload.get("first_clarify_request") is None

    # Run row should already carry the initial intake_draft snapshot.
    detail = test_client.get(f"/api/runs/{payload['run_id']}").json()
    assert detail["phase"] == "intake"
    assert detail["intake_draft"] is not None
    assert detail["intake_draft"]["user_query"] == "我想分析定价竞品"
    assert detail["plan_tree"] is None


def test_intake_create_honors_explicit_response_language(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs/intake",
        json={
            "user_query": "请帮我分析国内 AI CRM 厂商",
            "response_language": "en",
        },
    )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["intake_draft"]["response_language"] == "en"

    detail = test_client.get(f"/api/runs/{payload['run_id']}").json()
    assert detail["intake_draft"]["response_language"] == "en"


def test_legacy_create_honors_explicit_response_language(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs",
        json={
            "user_query": "请分析 Cursor 与 Windsurf 的定价差异",
            "competitors": ["Cursor", "Windsurf"],
            "response_language": "en",
        },
    )
    assert response.status_code == 200, response.text
    run_id = response.json()["run_id"]
    detail = test_client.get(f"/api/runs/{run_id}").json()
    assert detail["intake_draft"]["response_language"] == "en"


def test_run_evidence_api_exposes_language_and_translation_fields(test_client: TestClient) -> None:
    suffix = uuid4().hex[:8]
    run_id = f"run_evidence_lang_{suffix}"
    step_id = f"step_evidence_lang_{suffix}"
    evidence_en_id = f"ev_evidence_en_{suffix}"
    evidence_ja_id = f"ev_evidence_ja_{suffix}"
    now = datetime.now(timezone.utc)

    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO runs ("
                    "run_id, user_query, status, target_roles, competitors, started_at, created_at"
                    ") VALUES ("
                    ":run_id, :user_query, :status, "
                    "CAST(:target_roles AS jsonb), CAST(:competitors AS jsonb), :started_at, :created_at"
                    ")"
                ),
                {
                    "run_id": run_id,
                    "user_query": "evidence language api test",
                    "status": "completed",
                    "target_roles": "[]",
                    "competitors": json.dumps(["comp_cursor"], ensure_ascii=False),
                    "started_at": now,
                    "created_at": now,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO steps (step_id, run_id, agent_name, status, retry_count, payload, created_at) "
                    "VALUES (:step_id, :run_id, 'researcher', 'completed', 0, CAST(:payload AS jsonb), :created_at)"
                ),
                {"step_id": step_id, "run_id": run_id, "payload": "{}", "created_at": now},
            )
            connection.execute(
                text(
                    "INSERT INTO evidence ("
                    "id, run_id, source_type, source_url, source_title, quote, sanitized_text, span, "
                    "collected_by, collected_at, desensitized"
                    ") VALUES ("
                    ":id, :run_id, 'article', :source_url, :source_title, :quote, :sanitized_text, "
                    "CAST(:span AS jsonb), :collected_by, :collected_at, :desensitized"
                    ")"
                ),
                {
                    "id": evidence_en_id,
                    "run_id": run_id,
                    "source_url": "https://example.com/en-source",
                    "source_title": "English Source",
                    "quote": "Original English evidence text about pricing and procurement workflow.",
                    "sanitized_text": "Original English evidence text about pricing and procurement workflow.",
                    "span": json.dumps(
                        {
                            "competitor_id": "comp_cursor",
                            "source_language": "en",
                            "translated_excerpt": "这条证据说明了定价与采购流程。",
                        },
                        ensure_ascii=False,
                    ),
                    "collected_by": step_id,
                    "collected_at": now,
                    "desensitized": True,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO evidence ("
                    "id, run_id, source_type, source_url, source_title, quote, sanitized_text, span, "
                    "collected_by, collected_at, desensitized"
                    ") VALUES ("
                    ":id, :run_id, 'article', :source_url, :source_title, :quote, :sanitized_text, "
                    "CAST(:span AS jsonb), :collected_by, :collected_at, :desensitized"
                    ")"
                ),
                {
                    "id": evidence_ja_id,
                    "run_id": run_id,
                    "source_url": "https://example.com/ja-source",
                    "source_title": "Japanese Source",
                    "quote": "Cursorの価格情報と企業導入プロセスについて説明します。",
                    "sanitized_text": "Cursorの価格情報と企業導入プロセスについて説明します。",
                    "span": json.dumps(
                        {
                            "competitor_id": "comp_cursor",
                            "detected_language": "ja",
                        },
                        ensure_ascii=False,
                    ),
                    "collected_by": step_id,
                    "collected_at": now,
                    "desensitized": True,
                },
            )
    finally:
        engine.dispose()

    response = test_client.get(f"/api/runs/{run_id}/evidence")
    assert response.status_code == 200, response.text
    payload = response.json()
    by_id = {item["evidence_id"]: item for item in payload}

    english_item = by_id[evidence_en_id]
    assert english_item["quote"].startswith("Original English evidence text")
    assert english_item["source_language"] == "en"
    assert english_item["translated_excerpt"] == "这条证据说明了定价与采购流程。"

    japanese_item = by_id[evidence_ja_id]
    assert japanese_item["quote"].startswith("Cursorの価格情報")
    assert japanese_item["source_language"] == "ja"
    assert japanese_item["translated_excerpt"] is None


def test_intake_expert_mode_returns_422(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs/intake?mode=expert",
        json={
            "user_query": "Notion vs Cursor pricing",
            "user_role": "pm",
            "competitors_explicit": ["Notion", "Cursor"],
        },
    )
    assert response.status_code == 422
    body = response.json()
    assert body["error_code"] == "EXPERT_MODE_NOT_AVAILABLE"


def test_intake_create_idempotency_replay_returns_same_run(test_client: TestClient) -> None:
    key = "test-idempotency-key"
    first = test_client.post(
        "/api/runs/intake",
        headers={"Idempotency-Key": key},
        json={"user_query": "我想分析定价竞品"},
    )
    assert first.status_code == 200, first.text
    first_payload = first.json()

    second = test_client.post(
        "/api/runs/intake",
        headers={"Idempotency-Key": key},
        json={"user_query": "我想分析定价竞品"},
    )
    assert second.status_code == 200, second.text
    second_payload = second.json()
    assert second_payload["run_id"] == first_payload["run_id"]


def test_intake_create_idempotency_conflict_returns_409(test_client: TestClient) -> None:
    key = "test-idempotency-conflict-key"
    first = test_client.post(
        "/api/runs/intake",
        headers={"Idempotency-Key": key},
        json={"user_query": "query A"},
    )
    assert first.status_code == 200, first.text

    second = test_client.post(
        "/api/runs/intake",
        headers={"Idempotency-Key": key},
        json={"user_query": "query B"},
    )
    assert second.status_code == 409, second.text
    body = second.json()
    assert body["error_code"] == "INTAKE_CREATE_IDEMPOTENCY_CONFLICT"


def test_intake_reply_completes_and_publishes_plan(test_client: TestClient) -> None:
    """Phase 2: after intake completion, depth selection resumes planner publish.

    The run pauses at planning_profile_wait after required intake fields are
    complete. One more /intake/reply (report_depth choice) resumes to planner,
    which publishes plan_tree and pauses at planner_wait.
    """
    create = test_client.post(
        "/api/runs/intake",
        json={"user_query": "我想分析定价竞品"},
    )
    assert create.status_code == 200
    run_id = create.json()["run_id"]

    _post_intake_reply_when_ready(
        test_client,
        run_id=run_id,
        body={"text": "pm", "selected_options": ["pm"]},
    )

    deadline = time.time() + 10.0
    while time.time() < deadline:
        detail = test_client.get(f"/api/runs/{run_id}").json()
        draft = detail.get("intake_draft") or {}
        if draft.get("user_role") == "pm":
            break
        time.sleep(0.1)
    else:
        pytest.fail("intake_draft.user_role never merged after first reply")

    _post_intake_reply_when_ready(
        test_client,
        run_id=run_id,
        body={"text": "对比 Notion 和 Cursor 的定价策略"},
    )

    deadline = time.time() + 10.0
    while time.time() < deadline:
        draft = (test_client.get(f"/api/runs/{run_id}").json() or {}).get("intake_draft") or {}
        if draft.get("analysis_intent"):
            break
        time.sleep(0.1)
    else:
        pytest.fail("intake_draft.analysis_intent never merged after second reply")

    # Reply 3: completes required intake fields; graph pauses at profile gate.
    _post_intake_reply_when_ready(
        test_client,
        run_id=run_id,
        body={"text": "Notion, Cursor", "selected_options": ["已有名单"]},
    )

    deadline = time.time() + 10.0
    while time.time() < deadline:
        detail = test_client.get(f"/api/runs/{run_id}").json()
        if detail.get("phase") == "planning" and detail.get("plan_tree") is None:
            break
        time.sleep(0.1)
    else:
        pytest.fail("run never entered planning gate after intake completion")

    # Reply 4: confirm depth profile; planner can now publish and pause at planner_wait.
    _post_intake_reply_when_ready(
        test_client,
        run_id=run_id,
        body={"text": "", "selected_options": ["quick"]},
    )

    # Poll Run.plan_tree until the planner has published. Run.status stays
    # "running" because the graph is paused at planner_wait, not terminal.
    deadline = time.time() + 30.0
    while time.time() < deadline:
        detail = test_client.get(f"/api/runs/{run_id}").json()
        if detail.get("plan_tree") is not None:
            break
        time.sleep(0.1)
    else:
        pytest.fail("plan_tree never published after intake completed")

    detail = test_client.get(f"/api/runs/{run_id}").json()
    assert detail["status"] == "running"
    assert detail["phase"] == "planning"
    plan = detail["plan_tree"]
    assert plan is not None
    assert plan["confirmed_at"] is None
    assert plan["version"] == 1
    stages = [task["stage"] for task in plan["tasks"]]
    # Fake planner emits: research(Notion), research(Cursor), analyze, write.
    assert "research" in stages
    assert "analyze" in stages
    assert "write" in stages

    draft = detail["intake_draft"]
    assert draft is not None
    assert draft["user_role"] == "pm"
    assert "Notion" in draft["competitors_explicit"]


def test_intake_reply_rejects_when_not_paused(test_client: TestClient) -> None:
    # Legacy POST /api/runs creates a run that runs straight through supervisor
    # to a terminal state without ever pausing at intake_wait.
    response = test_client.post(
        "/api/runs",
        json={
            "user_query": "legacy run",
            "competitors": ["comp_a", "comp_b"],
        },
    )
    run_id = response.json()["run_id"]
    final_status = _wait_for_run_status(
        run_id,
        expected_statuses={"completed", "degraded", "failed"},
        timeout_seconds=120.0,
    )
    assert final_status in {"completed", "degraded"}

    reply = test_client.post(
        f"/api/runs/{run_id}/intake/reply",
        json={"text": "should be rejected"},
    )
    assert reply.status_code == 409
    body = reply.json()
    assert body["error_code"] == "RUN_NOT_RESUMABLE"


def test_legacy_run_create_requires_user_query(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs",
        json={"competitors": ["comp_a", "comp_b"]},
    )

    assert response.status_code == 422


def test_intake_reply_empty_payload_returns_422(test_client: TestClient) -> None:
    create = test_client.post(
        "/api/runs/intake",
        json={"user_query": "empty reply test"},
    )
    assert create.status_code == 200
    run_id = create.json()["run_id"]
    _post_intake_reply_when_ready(
        test_client,
        run_id=run_id,
        body={"text": "pm", "selected_options": ["pm"]},
    )
    reply = test_client.post(f"/api/runs/{run_id}/intake/reply", json={"text": "", "selected_options": []})
    assert reply.status_code == 422


def test_intake_create_from_run_inherits_context_and_sets_lineage(test_client: TestClient) -> None:
    parent = test_client.post(
        "/api/runs/intake",
        json={"user_query": "parent landscape run"},
    )
    assert parent.status_code == 200, parent.text
    parent_run_id = parent.json()["run_id"]

    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE runs SET "
                    "intake_draft = CAST(:intake_draft AS jsonb), "
                    "seed_competitor_ids = CAST(:seed_ids AS jsonb), "
                    "competitors = CAST(:competitors AS jsonb), "
                    "domain_hint = :domain_hint "
                    "WHERE run_id = :run_id"
                ),
                {
                    "run_id": parent_run_id,
                    "intake_draft": json.dumps(
                        {
                            "user_query": "parent landscape run",
                            "domain_hint": "AI hardware",
                            "self_product": "My smart glasses",
                            "market_scope": "global",
                            "analysis_archetype": "landscape",
                            "competitors_explicit": ["Meta Ray-Ban", "XREAL"],
                            "competitors_discovery_mode": False,
                            "focus_dimensions": ["market_differences"],
                            "report_depth": "quick",
                            "reference_urls": [],
                            "response_language": "zh",
                        },
                        ensure_ascii=False,
                    ),
                    "seed_ids": json.dumps(["Meta Ray-Ban", "XREAL"], ensure_ascii=False),
                    "competitors": json.dumps(["Meta Ray-Ban", "XREAL"], ensure_ascii=False),
                    "domain_hint": "AI hardware",
                },
            )
    finally:
        engine.dispose()

    create_child = test_client.post(
        "/api/runs/intake",
        json={
            "user_query": "请聚焦标杆产品，做三件套对比。",
            "from_run_id": parent_run_id,
            "seed_competitor_ids": ["Meta Ray-Ban"],
        },
    )
    assert create_child.status_code == 200, create_child.text
    child_payload = create_child.json()
    assert child_payload["intake_draft"]["analysis_archetype"] == "comparison"
    assert child_payload["intake_draft"]["competitors_explicit"] == ["Meta Ray-Ban"]
    assert child_payload["intake_draft"]["competitors_discovery_mode"] is False
    assert child_payload["intake_draft"]["domain_hint"] == "AI hardware"
    assert child_payload["intake_draft"]["self_product"] == "My smart glasses"
    assert child_payload["intake_draft"]["market_scope"] == "global"

    child_detail = test_client.get(f"/api/runs/{child_payload['run_id']}")
    assert child_detail.status_code == 200, child_detail.text
    child_data = child_detail.json()
    assert child_data["parent_run_id"] == parent_run_id
    assert child_data["seed_competitor_ids"] == ["Meta Ray-Ban"]


def test_intake_create_from_run_not_found_returns_404(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs/intake",
        json={
            "user_query": "focus run not found",
            "from_run_id": "run_not_exists",
        },
    )
    assert response.status_code == 404, response.text
    payload = response.json()
    assert payload["error_code"] == "FROM_RUN_NOT_FOUND"


# Phase 2: POST /api/runs/{id}/plan/confirm tests live in test_plan_api.py so
# this module stays focused on intake-stage assertions.
