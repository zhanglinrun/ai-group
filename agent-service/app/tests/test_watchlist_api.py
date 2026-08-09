from __future__ import annotations

from datetime import datetime, timedelta, timezone
import json
from uuid import uuid4

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from core.config import settings


def _insert_watchlist_digest_fixture() -> dict[str, object]:
    suffix = uuid4().hex[:8]
    competitor_base = f"Meta Ray-Ban {suffix}"
    competitor_alias = f"Ray-Ban Meta {suffix}"
    run_old_id = f"run_watch_old_{suffix}"
    run_new_id = f"run_watch_new_{suffix}"
    step_old_id = f"step_watch_old_{suffix}"
    step_new_id = f"step_watch_new_{suffix}"
    conclusion_old_id = f"concl_watch_old_{suffix}"
    conclusion_new_id = f"concl_watch_new_{suffix}"
    diff_id = f"diff_watch_{suffix}"
    watch_id = f"watch_{suffix}"
    evidence_old_id = f"ev_watch_old_{suffix}"
    evidence_new_primary_id = f"ev_watch_new_a_{suffix}"
    evidence_new_secondary_id = f"ev_watch_new_b_{suffix}"

    now = datetime.now(timezone.utc)
    old_time = now - timedelta(hours=4)
    new_time = now - timedelta(hours=1)

    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO runs ("
                    "run_id, user_query, title, status, target_roles, competitors, "
                    "plan_tree, started_at, created_at"
                    ") VALUES ("
                    ":run_id, :user_query, :title, :status, "
                    "CAST(:target_roles AS jsonb), CAST(:competitors AS jsonb), "
                    "CAST(:plan_tree AS jsonb), :started_at, :created_at"
                    ")"
                ),
                {
                    "run_id": run_old_id,
                    "user_query": "watch digest old run",
                    "title": "Cursor pricing baseline",
                    "status": "completed",
                    "target_roles": "[]",
                    "competitors": json.dumps([competitor_base], ensure_ascii=False),
                    "plan_tree": "{}",
                    "started_at": old_time,
                    "created_at": old_time,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO runs ("
                    "run_id, user_query, title, status, target_roles, competitors, "
                    "plan_tree, started_at, created_at"
                    ") VALUES ("
                    ":run_id, :user_query, :title, :status, "
                    "CAST(:target_roles AS jsonb), CAST(:competitors AS jsonb), "
                    "CAST(:plan_tree AS jsonb), :started_at, :created_at"
                    ")"
                ),
                {
                    "run_id": run_new_id,
                    "user_query": "Cursor launch recap",
                    "title": None,
                    "status": "completed",
                    "target_roles": "[]",
                    "competitors": json.dumps([competitor_base], ensure_ascii=False),
                    "plan_tree": json.dumps(
                        {
                            "competitor_sources": {
                                competitor_base: {
                                    "candidate_role": "direct_competitor",
                                    "segment": "AI smart glasses",
                                    "vendor": "Meta",
                                    "introduction": "Meta and Ray-Ban's camera-first smart glasses product.",
                                }
                            }
                        },
                        ensure_ascii=False,
                    ),
                    "started_at": new_time,
                    "created_at": new_time,
                },
            )

            connection.execute(
                text(
                    "INSERT INTO steps (step_id, run_id, agent_name, status, retry_count, payload, created_at) "
                    "VALUES (:step_id, :run_id, 'analyst', 'completed', 0, CAST(:payload AS jsonb), :created_at)"
                ),
                {"step_id": step_old_id, "run_id": run_old_id, "payload": "{}", "created_at": old_time},
            )
            connection.execute(
                text(
                    "INSERT INTO steps (step_id, run_id, agent_name, status, retry_count, payload, created_at) "
                    "VALUES (:step_id, :run_id, 'analyst', 'completed', 0, CAST(:payload AS jsonb), :created_at)"
                ),
                {"step_id": step_new_id, "run_id": run_new_id, "payload": "{}", "created_at": new_time},
            )

            for evidence_id, run_id, step_id in (
                (evidence_old_id, run_old_id, step_old_id),
                (evidence_new_primary_id, run_new_id, step_new_id),
                (evidence_new_secondary_id, run_new_id, step_new_id),
            ):
                connection.execute(
                    text(
                        "INSERT INTO evidence ("
                        "id, run_id, source_type, source_url, source_title, quote, sanitized_text, "
                        "span, collected_by, collected_at, desensitized"
                        ") VALUES ("
                        ":id, :run_id, 'article', :source_url, :source_title, :quote, :sanitized_text, "
                        "CAST(:span AS jsonb), :collected_by, :collected_at, :desensitized"
                        ")"
                    ),
                    {
                        "id": evidence_id,
                        "run_id": run_id,
                        "source_url": f"https://example.com/{evidence_id}",
                        "source_title": f"source {evidence_id}",
                        "quote": f"quote {evidence_id}",
                        "sanitized_text": f"sanitized {evidence_id}",
                        "span": json.dumps({"competitor_id": competitor_base}, ensure_ascii=False),
                        "collected_by": step_id,
                        "collected_at": new_time,
                        "desensitized": True,
                    },
                )

            connection.execute(
                text(
                    "INSERT INTO conclusions ("
                    "conclusion_id, run_id, step_id, section, claim, confidence, competitor_ids, risk_flags, created_at"
                    ") VALUES ("
                    ":conclusion_id, :run_id, :step_id, :section, :claim, :confidence, "
                    "CAST(:competitor_ids AS jsonb), CAST(:risk_flags AS jsonb), :created_at"
                    ")"
                ),
                {
                    "conclusion_id": conclusion_old_id,
                    "run_id": run_old_id,
                    "step_id": step_old_id,
                    "section": "pricing",
                    "claim": "Old pricing signal.",
                    "confidence": "medium",
                    "competitor_ids": json.dumps([competitor_base], ensure_ascii=False),
                    "risk_flags": "[]",
                    "created_at": old_time,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO conclusions ("
                    "conclusion_id, run_id, step_id, section, claim, confidence, competitor_ids, risk_flags, created_at"
                    ") VALUES ("
                    ":conclusion_id, :run_id, :step_id, :section, :claim, :confidence, "
                    "CAST(:competitor_ids AS jsonb), CAST(:risk_flags AS jsonb), :created_at"
                    ")"
                ),
                {
                    "conclusion_id": conclusion_new_id,
                    "run_id": run_new_id,
                    "step_id": step_new_id,
                    "section": "feature",
                    "claim": "New feature launch signal.",
                    "confidence": "high",
                    "competitor_ids": json.dumps([f"  {competitor_alias.upper()}  "], ensure_ascii=False),
                    "risk_flags": "[]",
                    "created_at": new_time,
                },
            )

            connection.execute(
                text(
                    "INSERT INTO conclusion_evidence (conclusion_id, evidence_id, relevance_rank) "
                    "VALUES (:conclusion_id, :evidence_id, :relevance_rank)"
                ),
                {
                    "conclusion_id": conclusion_old_id,
                    "evidence_id": evidence_old_id,
                    "relevance_rank": 0,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO conclusion_evidence (conclusion_id, evidence_id, relevance_rank) "
                    "VALUES (:conclusion_id, :evidence_id, :relevance_rank)"
                ),
                {
                    "conclusion_id": conclusion_new_id,
                    "evidence_id": evidence_new_secondary_id,
                    "relevance_rank": 1,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO conclusion_evidence (conclusion_id, evidence_id, relevance_rank) "
                    "VALUES (:conclusion_id, :evidence_id, :relevance_rank)"
                ),
                {
                    "conclusion_id": conclusion_new_id,
                    "evidence_id": evidence_new_primary_id,
                    "relevance_rank": 0,
                },
            )

            connection.execute(
                text(
                    "INSERT INTO competitor_diffs ("
                    "diff_id, competitor_id, run_id_new, run_id_old, dimension, change_type, "
                    "old_value, new_value, significance, created_at"
                    ") VALUES ("
                    ":diff_id, :competitor_id, :run_id_new, :run_id_old, :dimension, :change_type, "
                    "CAST(:old_value AS jsonb), CAST(:new_value AS jsonb), :significance, :created_at"
                    ")"
                ),
                {
                    "diff_id": diff_id,
                    "competitor_id": competitor_alias,
                    "run_id_new": run_new_id,
                    "run_id_old": run_old_id,
                    "dimension": "pricing",
                    "change_type": "summary_changed",
                    "old_value": json.dumps({"stance": "competitive", "summary": "Old price."}),
                    "new_value": json.dumps({"stance": "competitive", "summary": "New price."}),
                    "significance": "low",
                    "created_at": new_time,
                },
            )

            connection.execute(
                text(
                    "INSERT INTO watchlist (watch_id, competitor_id, note, last_run_id, created_at) "
                    "VALUES (:watch_id, :competitor_id, :note, :last_run_id, :created_at)"
                ),
                {
                    "watch_id": watch_id,
                    "competitor_id": competitor_base,
                    "note": "pricing and launch",
                    "last_run_id": run_new_id,
                    "created_at": now,
                },
            )
    finally:
        engine.dispose()

    return {
        "watch_id": watch_id,
        "run_old_id": run_old_id,
        "run_new_id": run_new_id,
        "conclusion_old_id": conclusion_old_id,
        "conclusion_new_id": conclusion_new_id,
        "diff_id": diff_id,
        "evidence_old_id": evidence_old_id,
        "evidence_new_ids": [evidence_new_primary_id, evidence_new_secondary_id],
    }


def _cleanup_watchlist_digest_fixture(*, watch_id: str, run_ids: list[str]) -> None:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text("DELETE FROM watchlist WHERE watch_id = :watch_id"),
                {"watch_id": watch_id},
            )
            for run_id in run_ids:
                connection.execute(
                    text("DELETE FROM competitor_diffs WHERE run_id_new = :run_id OR run_id_old = :run_id"),
                    {"run_id": run_id},
                )
                connection.execute(
                    text("DELETE FROM runs WHERE run_id = :run_id"),
                    {"run_id": run_id},
                )
    finally:
        engine.dispose()


def test_watchlist_digest_groups_conclusions_case_insensitive(test_client: TestClient) -> None:
    fixture = _insert_watchlist_digest_fixture()
    try:
        response = test_client.get("/api/watchlist/digest")
        assert response.status_code == 200, response.text
        payload = response.json()

        watch_item = next(
            item
            for item in payload
            if item["watch_id"] == fixture["watch_id"]
        )
        assert watch_item["insight_count"] == 2
        assert watch_item["run_count"] == 2
        assert watch_item["latest_run_id"] == fixture["run_new_id"]
        assert watch_item["last_updated_at"] is not None
        assert watch_item["profile"] == {
            "competitor_id": f"Meta Ray-Ban {str(fixture['watch_id']).replace('watch_', '')}",
            "role": "direct_competitor",
            "segment": "AI smart glasses",
            "vendor": "Meta",
            "introduction": "Meta and Ray-Ban's camera-first smart glasses product.",
        }
        assert [diff["diff_id"] for diff in watch_item["recent_changes"]] == [fixture["diff_id"]]
        assert len(watch_item["items"]) == 2
        assert watch_item["delta"] == {
            "latest_run_id": fixture["run_new_id"],
            "previous_run_id": fixture["run_old_id"],
            "added_claims": ["New feature launch signal."],
            "removed_claims": ["Old pricing signal."],
        }

        latest_item = watch_item["items"][0]
        previous_item = watch_item["items"][1]

        assert latest_item["conclusion_id"] == fixture["conclusion_new_id"]
        assert latest_item["run_id"] == fixture["run_new_id"]
        assert latest_item["run_title"] == "Cursor launch recap"
        assert latest_item["evidence_ids"] == fixture["evidence_new_ids"]

        assert previous_item["conclusion_id"] == fixture["conclusion_old_id"]
        assert previous_item["run_id"] == fixture["run_old_id"]
        assert previous_item["run_title"] == "Cursor pricing baseline"
        assert previous_item["evidence_ids"] == [fixture["evidence_old_id"]]
    finally:
        _cleanup_watchlist_digest_fixture(
            watch_id=str(fixture["watch_id"]),
            run_ids=[str(fixture["run_old_id"]), str(fixture["run_new_id"])],
        )


def test_watchlist_create_blocks_alias_duplicate(test_client: TestClient) -> None:
    suffix = uuid4().hex[:6]
    primary_name = f"Meta Ray-Ban {suffix}"
    alias_name = f"Ray-Ban Meta {suffix}"
    created_watch_id: str | None = None
    try:
        create_response = test_client.post(
            "/api/watchlist",
            json={"competitor_id": primary_name},
        )
        assert create_response.status_code == 200, create_response.text
        created_watch_id = create_response.json()["watch_id"]

        duplicate_response = test_client.post(
            "/api/watchlist",
            json={"competitor_id": alias_name},
        )
        assert duplicate_response.status_code == 409, duplicate_response.text
        payload = duplicate_response.json()
        assert payload["error_code"] == "WATCHLIST_ALREADY_EXISTS"
    finally:
        if created_watch_id is not None:
            test_client.delete(f"/api/watchlist/{created_watch_id}")
