from __future__ import annotations

import json
from uuid import uuid4

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from core.config import settings


def _insert_run_with_optional_knowledge(*, with_knowledge: bool) -> str:
    run_id = f"run_knowledge_api_{uuid4().hex[:8]}"
    step_id = f"step_knowledge_api_{uuid4().hex[:8]}"
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO runs "
                    "(run_id, user_query, domain_hint, reference_urls, status, target_roles, competitors) "
                    "VALUES (:run_id, :user_query, :domain_hint, CAST(:reference_urls AS jsonb), "
                    ":status, CAST(:target_roles AS jsonb), CAST(:competitors AS jsonb))"
                ),
                {
                    "run_id": run_id,
                    "user_query": "knowledge api test",
                    "domain_hint": "ai_coding_tools",
                    "reference_urls": "[]",
                    "status": "completed",
                    "target_roles": '["pm"]',
                    "competitors": '["Cursor"]',
                },
            )
            connection.execute(
                text(
                    "INSERT INTO steps "
                    "(step_id, run_id, agent_name, status, retry_count, payload) "
                    "VALUES (:step_id, :run_id, 'analyst', 'completed', 0, CAST(:payload AS jsonb))"
                ),
                {"step_id": step_id, "run_id": run_id, "payload": "{}"},
            )
            if with_knowledge:
                connection.execute(
                    text(
                        "INSERT INTO run_knowledge "
                        "(knowledge_id, run_id, step_id, schema_version, features, pricings, personas, feedback, missing_reasons, coverage) "
                        "VALUES (:knowledge_id, :run_id, :step_id, :schema_version, "
                        "CAST(:features AS jsonb), CAST(:pricings AS jsonb), "
                        "CAST(:personas AS jsonb), CAST(:feedback AS jsonb), CAST(:missing_reasons AS jsonb), CAST(:coverage AS jsonb))"
                    ),
                    {
                        "knowledge_id": f"knowledge_{uuid4().hex[:8]}",
                        "run_id": run_id,
                        "step_id": step_id,
                        "schema_version": "schema_v0.2",
                        "features": json.dumps(
                            [
                                {
                                    "id": "feat_cursor_repo",
                                    "competitor_id": "Cursor",
                                    "name": "Repository context",
                                    "parent_id": None,
                                    "description": "Understands repository context.",
                                    "maturity": "advanced",
                                    "evidence_ids": ["ev_cursor_feature"],
                                }
                            ],
                            ensure_ascii=False,
                        ),
                        "pricings": json.dumps(
                            [
                                {
                                    "id": "price_cursor_unknown",
                                    "competitor_id": "Cursor",
                                    "model": "unknown",
                                    "tiers": [],
                                    "free_plan": None,
                                    "enterprise_plan": True,
                                    "evidence_ids": ["ev_cursor_pricing"],
                                }
                            ],
                            ensure_ascii=False,
                        ),
                        "personas": json.dumps(
                            [
                                {
                                    "id": "persona_eng_manager",
                                    "competitor_id": "Cursor",
                                    "name": "Engineering manager",
                                    "role": "engineering_manager",
                                    "pain_points": ["Review load"],
                                    "jobs_to_be_done": ["Improve delivery"],
                                    "evidence_ids": ["ev_cursor_feedback"],
                                }
                            ],
                            ensure_ascii=False,
                        ),
                        "feedback": json.dumps(
                            [
                                {
                                    "id": "fb_cursor_docs",
                                    "competitor_id": "Cursor",
                                    "sentiment": "neutral",
                                    "topic": "onboarding",
                                    "summary": "Users asked for clearer onboarding docs.",
                                    "evidence_ids": ["ev_cursor_feedback"],
                                }
                            ],
                            ensure_ascii=False,
                        ),
                        "coverage": json.dumps(
                            {"Cursor": {"feature": "partial", "pricing": "complete"}},
                            ensure_ascii=False,
                        ),
                        "missing_reasons": json.dumps(
                            {"Cursor": ["feature:coverage_partial"]},
                            ensure_ascii=False,
                        ),
                    },
                )
    finally:
        engine.dispose()
    return run_id


def _delete_run(run_id: str) -> None:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text("DELETE FROM runs WHERE run_id = :run_id"),
                {"run_id": run_id},
            )
    finally:
        engine.dispose()


def test_get_run_knowledge_returns_persisted_schema(test_client: TestClient) -> None:
    run_id = _insert_run_with_optional_knowledge(with_knowledge=True)
    try:
        response = test_client.get(f"/api/runs/{run_id}/knowledge")
        payload = response.json()

        assert response.status_code == 200
        assert payload["run_id"] == run_id
        assert payload["analysis_archetype"] == "comparison"
        assert payload["schema_version"] == "schema_v0.2"
        assert payload["features"][0]["id"] == "feat_cursor_repo"
        assert payload["pricings"][0]["model"] == "unknown"
        assert payload["personas"][0]["role"] == "engineering_manager"
        assert payload["feedback"][0]["topic"] == "onboarding"
        assert payload["missing_reasons"] == {"Cursor": ["feature:coverage_partial"]}
        assert payload["coverage"] == {"Cursor": {"feature": "partial", "pricing": "complete"}}
    finally:
        _delete_run(run_id)


def test_get_run_knowledge_returns_empty_payload_when_no_knowledge_exists(
    test_client: TestClient,
) -> None:
    run_id = _insert_run_with_optional_knowledge(with_knowledge=False)
    try:
        response = test_client.get(f"/api/runs/{run_id}/knowledge")
        payload = response.json()

        assert response.status_code == 200
        assert payload == {
            "run_id": run_id,
            "analysis_archetype": "comparison",
            "schema_version": "schema_v0.2",
            "competitors": [],
            "features": [],
            "pricings": [],
            "personas": [],
            "feedback": [],
            "missing_reasons": {},
            "coverage": {},
        }
    finally:
        _delete_run(run_id)


def test_get_run_knowledge_returns_404_for_missing_run(test_client: TestClient) -> None:
    response = test_client.get(f"/api/runs/run_missing_{uuid4().hex[:8]}/knowledge")

    assert response.status_code == 404
    assert response.json()["error_code"] == "RUN_NOT_FOUND"
