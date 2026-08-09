from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from core.config import settings
from schemas.ids import make_id
from service.skill_promotion import PromotionWriteError


def _insert_staging_candidate(candidate_id: str) -> None:
    payload_json = json.dumps(
        {
            "rule_yaml": (
                "id: rule_promoted_for_router_test\n"
                "when:\n"
                "  section_id_in: [pricing]\n"
                "require:\n"
                "  evidence_refs_count_gte: 1\n"
            ),
            "triggered_failures_count": 1,
            "similar_existing_rules": [],
        },
        ensure_ascii=False,
    )
    supporting_run_ids = json.dumps([make_id("run_")], ensure_ascii=False)
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO skill_candidates "
                    "(id, candidate_type, applies_to, tags, payload, rationale, supporting_run_ids, confidence, status, error) "
                    "VALUES (:id, :candidate_type, :applies_to, CAST(:tags AS jsonb), CAST(:payload AS jsonb), :rationale, CAST(:supporting_run_ids AS jsonb), :confidence, :status, :error)"
                ),
                {
                    "id": candidate_id,
                    "candidate_type": "qa_rule",
                    "applies_to": "qa_rule",
                    "tags": json.dumps(["pricing", "quality"], ensure_ascii=False),
                    "payload": payload_json,
                    "rationale": "router promotion test",
                    "supporting_run_ids": supporting_run_ids,
                    "confidence": "medium",
                    "status": "staging",
                    "error": None,
                },
            )
    finally:
        engine.dispose()


def _insert_invalid_rule_candidate(candidate_id: str) -> None:
    payload_json = json.dumps(
        {
            "rule_yaml": "id: rule_invalid_for_router_test\nselector: report\nchecks: []",
            "triggered_failures_count": 1,
            "similar_existing_rules": [],
        },
        ensure_ascii=False,
    )
    supporting_run_ids = json.dumps([make_id("run_")], ensure_ascii=False)
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO skill_candidates "
                    "(id, candidate_type, applies_to, tags, payload, rationale, supporting_run_ids, confidence, status, error) "
                    "VALUES (:id, :candidate_type, :applies_to, CAST(:tags AS jsonb), CAST(:payload AS jsonb), :rationale, CAST(:supporting_run_ids AS jsonb), :confidence, :status, :error)"
                ),
                {
                    "id": candidate_id,
                    "candidate_type": "qa_rule",
                    "applies_to": "qa_rule",
                    "tags": json.dumps(["pricing", "quality"], ensure_ascii=False),
                    "payload": payload_json,
                    "rationale": "invalid router promotion test",
                    "supporting_run_ids": supporting_run_ids,
                    "confidence": "medium",
                    "status": "staging",
                    "error": None,
                },
            )
    finally:
        engine.dispose()


def _delete_candidate(candidate_id: str) -> None:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text("DELETE FROM skill_candidates WHERE id = :id"),
                {"id": candidate_id},
            )
    finally:
        engine.dispose()


def _query_candidate_status(candidate_id: str) -> str:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.connect() as connection:
            row = connection.execute(
                text("SELECT status FROM skill_candidates WHERE id = :id"),
                {"id": candidate_id},
            ).mappings().first()
    finally:
        engine.dispose()
    if row is None:
        raise RuntimeError(f"Missing candidate_id={candidate_id}")
    return str(row["status"])


def test_approve_skill_candidate_promotes_artifacts(
    test_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    candidate_id = make_id("skill_")
    _insert_staging_candidate(candidate_id)
    monkeypatch.setattr("router.skill_rt._skills_root", lambda: tmp_path)
    try:
        response = test_client.post(
            f"/api/skill-candidates/{candidate_id}/approve",
            json={"reviewed_by": "owner_wh"},
        )
        payload = response.json()
        assert response.status_code == 200
        assert payload["status"] == "approved"
        assert payload["promoted_artifacts"]
        assert payload["promoted_artifacts"][0]["path"].endswith("SKILL.md")
        entry_id = str(payload["promoted_artifacts"][0]["entry_id"])
        promoted_file = tmp_path / "qa_rule" / entry_id / "SKILL.md"
        assert promoted_file.exists()
    finally:
        _delete_candidate(candidate_id)


def test_approve_skill_candidate_rolls_back_on_write_error(
    test_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    candidate_id = make_id("skill_")
    _insert_staging_candidate(candidate_id)

    def _raise_write_error(**_: object) -> list[dict[str, str]]:
        raise PromotionWriteError("simulated write failure")

    monkeypatch.setattr("router.skill_rt.promote_approved_candidate", _raise_write_error)
    try:
        response = test_client.post(
            f"/api/skill-candidates/{candidate_id}/approve",
            json={"reviewed_by": "owner_wh"},
        )
        payload = response.json()
        assert response.status_code == 500
        assert payload["error_code"] == "PROMOTION_WRITE_FAILED"
        status = _query_candidate_status(candidate_id)
        assert status == "staging"
    finally:
        _delete_candidate(candidate_id)


def test_approve_skill_candidate_rejects_invalid_rule_and_keeps_staging(
    test_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    candidate_id = make_id("skill_")
    _insert_invalid_rule_candidate(candidate_id)
    monkeypatch.setattr("router.skill_rt._skills_root", lambda: tmp_path)
    try:
        response = test_client.post(
            f"/api/skill-candidates/{candidate_id}/approve",
            json={"reviewed_by": "owner_wh"},
        )
        payload = response.json()
        assert response.status_code == 422
        assert payload["error_code"] == "SKILL_CANDIDATE_RULE_INVALID"
        assert _query_candidate_status(candidate_id) == "staging"
        assert not list(tmp_path.rglob("SKILL.md"))
    finally:
        _delete_candidate(candidate_id)
