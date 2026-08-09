from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from core.config import settings
from schemas.ids import make_id


def _insert_staging_candidate(*, run_id: str, candidate_id: str) -> None:
    payload_json = json.dumps(
        {
            "rule_yaml": (
                "id: rule_review_test\n"
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
    supporting_run_ids = json.dumps([run_id], ensure_ascii=False)
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
                    "rationale": "skill review route test",
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


def test_list_skill_candidates(test_client: TestClient) -> None:
    run_id = make_id("run_")
    candidate_id = make_id("skill_")
    _insert_staging_candidate(run_id=run_id, candidate_id=candidate_id)
    try:
        response = test_client.get(
            "/api/skill-candidates",
            params={"status": "staging", "applies_to": "qa_rule", "limit": 20, "offset": 0},
        )
        payload = response.json()
        assert response.status_code == 200
        assert payload["total"] >= 1
        listed = next((item for item in payload["items"] if item["id"] == candidate_id), None)
        assert listed is not None
        assert listed["applies_to"] == "qa_rule"
        assert "pricing" in listed["tags"]
        assert run_id in listed["supporting_run_ids"]
        assert listed["status"] == "staging"
    finally:
        _delete_candidate(candidate_id)


def test_approve_skill_candidate(
    test_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    run_id = make_id("run_")
    candidate_id = make_id("skill_")
    _insert_staging_candidate(run_id=run_id, candidate_id=candidate_id)
    monkeypatch.setattr("router.skill_rt._skills_root", lambda: tmp_path)
    try:
        approve_response = test_client.post(
            f"/api/skill-candidates/{candidate_id}/approve",
            json={"reviewed_by": "owner_wh"},
        )
        approve_payload = approve_response.json()
        assert approve_response.status_code == 200
        assert approve_payload["id"] == candidate_id
        assert approve_payload["status"] == "approved"
        assert approve_payload["reviewed_by"] == "owner_wh"
        assert "promoted_artifacts" in approve_payload
        assert isinstance(approve_payload["promoted_artifacts"], list)
        assert approve_payload["promoted_artifacts"]
    finally:
        _delete_candidate(candidate_id)


def test_reject_skill_candidate(test_client: TestClient) -> None:
    run_id = make_id("run_")
    candidate_id = make_id("skill_")
    _insert_staging_candidate(run_id=run_id, candidate_id=candidate_id)
    try:
        reject_response = test_client.post(
            f"/api/skill-candidates/{candidate_id}/reject",
            json={"reviewed_by": "owner_wh"},
        )
        reject_payload = reject_response.json()
        assert reject_response.status_code == 200
        assert reject_payload["id"] == candidate_id
        assert reject_payload["status"] == "rejected"
        assert reject_payload["reviewed_by"] == "owner_wh"
        assert "promoted_artifacts" in reject_payload
        assert reject_payload["promoted_artifacts"] == []
    finally:
        _delete_candidate(candidate_id)


def test_approve_skill_candidate_rejects_non_staging(
    test_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    run_id = make_id("run_")
    candidate_id = make_id("skill_")
    _insert_staging_candidate(run_id=run_id, candidate_id=candidate_id)
    monkeypatch.setattr("router.skill_rt._skills_root", lambda: tmp_path)
    try:
        first_response = test_client.post(
            f"/api/skill-candidates/{candidate_id}/approve",
            json={"reviewed_by": "owner_wh"},
        )
        assert first_response.status_code == 200

        second_response = test_client.post(
            f"/api/skill-candidates/{candidate_id}/approve",
            json={"reviewed_by": "owner_wh"},
        )
        second_payload = second_response.json()
        assert second_response.status_code == 409
        assert second_payload["error_code"] == "SKILL_CANDIDATE_NOT_REVIEWABLE"
    finally:
        _delete_candidate(candidate_id)
