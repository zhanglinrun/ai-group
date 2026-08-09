from __future__ import annotations
import asyncio
from datetime import datetime, timezone
import json
from pathlib import Path
import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, delete, text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from agents.nodes.writer import writer_node
from agents.graph import build_graph_uncompiled
from core.config import settings
from models.evidence import EvidenceRecord
from models.run import Run
from models.step import Step
from schemas.agent_message import AgentMessage
from schemas.business import Evidence
from schemas.qa import Rejection, RetryPolicy
from schemas.skill import SkillCandidate
from schemas.supervisor import SupervisorDecision
from router.run_rt import _to_step_trace_response
from service.event_bus import RunEvent, RunEventType
from service.conclusion import persist_conclusions_for_step
from service.skill_store import get_skill_store


@pytest.fixture(autouse=True)
def _offline_research_channels(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "TAVILY_API_KEY", None)


def test_step_trace_response_exposes_rejection_reason() -> None:
    timestamp = datetime.now(timezone.utc)
    rejected_step = Step(
        step_id="step_qa_rejected",
        run_id="run_trace_contract",
        agent_name="qa",
        status="rejected",
        retry_count=0,
        payload={"qa_outcome": "rejected"},
        rejection_reason={
            "semantic_findings": [{"message": "missing evidence"}],
            "required_fields": ["evidence_refs"],
            "retry_policy": {"current_retry": 1},
        },
        started_at=timestamp,
        finished_at=timestamp,
        created_at=timestamp,
    )
    approved_step = Step(
        step_id="step_qa_approved",
        run_id="run_trace_contract",
        agent_name="qa",
        status="completed",
        retry_count=0,
        payload={"qa_outcome": "approved"},
        rejection_reason=None,
        started_at=timestamp,
        finished_at=timestamp,
        created_at=timestamp,
    )

    rejected_payload = _to_step_trace_response(rejected_step).model_dump()
    approved_payload = _to_step_trace_response(approved_step).model_dump()

    assert rejected_payload["rejection_reason"] == {
        "semantic_findings": [{"message": "missing evidence"}],
        "required_fields": ["evidence_refs"],
        "retry_policy": {"current_retry": 1},
    }
    assert approved_payload["rejection_reason"] is None


def _fetch_persisted_snapshot(run_id: str) -> dict[str, int | str | bool | float]:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.connect() as connection:
            run_row = connection.execute(
                text("SELECT status FROM runs WHERE run_id = :run_id"),
                {"run_id": run_id},
            ).mappings().first()
            step_count = connection.execute(
                text("SELECT COUNT(*) AS count FROM steps WHERE run_id = :run_id"),
                {"run_id": run_id},
            ).scalar_one()
            decision_row = connection.execute(
                text(
                    "SELECT chosen_tool FROM supervisor_decisions "
                    "WHERE run_id = :run_id ORDER BY created_at DESC LIMIT 1"
                ),
                {"run_id": run_id},
            ).mappings().first()
            first_decision_row = connection.execute(
                text(
                    "SELECT chosen_tool FROM supervisor_decisions "
                    "WHERE run_id = :run_id ORDER BY created_at ASC LIMIT 1"
                ),
                {"run_id": run_id},
            ).mappings().first()
            qa_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'qa'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            qa_rejection_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'qa' "
                    "AND rejection_reason IS NOT NULL"
                ),
                {"run_id": run_id},
            ).scalar_one()
            supervisor_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'supervisor'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            analyst_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'analyst'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            supervisor_llm_call_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM llm_calls l "
                    "JOIN steps s ON s.step_id = l.step_id "
                    "WHERE s.run_id = :run_id AND s.agent_name = 'supervisor'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            supervisor_llm_success_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM llm_calls l "
                    "JOIN steps s ON s.step_id = l.step_id "
                    "WHERE s.run_id = :run_id AND s.agent_name = 'supervisor' "
                    "AND l.model_slot = 'research' AND l.error IS NULL"
                ),
                {"run_id": run_id},
            ).scalar_one()
            supervisor_llm_prompt_hash_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM llm_calls l "
                    "JOIN steps s ON s.step_id = l.step_id "
                    "WHERE s.run_id = :run_id AND s.agent_name = 'supervisor' "
                    "AND l.prompt_hash IS NOT NULL"
                ),
                {"run_id": run_id},
            ).scalar_one()
            analyst_llm_call_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM llm_calls l "
                    "JOIN steps s ON s.step_id = l.step_id "
                    "WHERE s.run_id = :run_id AND s.agent_name = 'analyst'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            analyst_llm_summarization_slot_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM llm_calls l "
                    "JOIN steps s ON s.step_id = l.step_id "
                    "WHERE s.run_id = :run_id AND s.agent_name = 'analyst' "
                    "AND l.model_slot = 'summarization'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            analyst_fallback_mode_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'analyst' "
                    "AND payload ->> 'analysis_mode' = 'fallback'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            qa_llm_call_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM llm_calls l "
                    "JOIN steps s ON s.step_id = l.step_id "
                    "WHERE s.run_id = :run_id AND s.agent_name = 'qa' "
                    "AND l.model_slot = 'qa'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            qa_semantic_degraded_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'qa' "
                    "AND payload ->> 'qa_semantic_mode' = 'degraded_rule_only'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            writer_llm_call_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM llm_calls l "
                    "JOIN steps s ON s.step_id = l.step_id "
                    "WHERE s.run_id = :run_id AND s.agent_name = 'writer' "
                    "AND l.model_slot = 'writer'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            researcher_llm_call_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM llm_calls l "
                    "JOIN steps s ON s.step_id = l.step_id "
                    "WHERE s.run_id = :run_id AND s.agent_name = 'researcher'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            researcher_llm_research_slot_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM llm_calls l "
                    "JOIN steps s ON s.step_id = l.step_id "
                    "WHERE s.run_id = :run_id AND s.agent_name = 'researcher' "
                    "AND l.model_slot = 'research'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            researcher_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'researcher'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            researcher_started_span_seconds_raw = connection.execute(
                text(
                    "SELECT COALESCE(EXTRACT(EPOCH FROM (MAX(started_at) - MIN(started_at))), 0) AS span "
                    "FROM steps WHERE run_id = :run_id AND agent_name = 'researcher'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            checkpoint_row_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM checkpoints "
                    "WHERE thread_id = :run_id"
                ),
                {"run_id": run_id},
            ).scalar_one()
            checkpoint_writes_row_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM checkpoint_writes "
                    "WHERE thread_id = :run_id"
                ),
                {"run_id": run_id},
            ).scalar_one()
            evidence_count = connection.execute(
                text("SELECT COUNT(*) AS count FROM evidence WHERE run_id = :run_id"),
                {"run_id": run_id},
            ).scalar_one()
            structured_evidence_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM evidence "
                    "WHERE run_id = :run_id AND source_type = 'article'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            evidence_url_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM evidence "
                    "WHERE run_id = :run_id AND source_url IS NOT NULL"
                ),
                {"run_id": run_id},
            ).scalar_one()
            expected_phrase_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM evidence "
                    "WHERE run_id = :run_id AND "
                    "sanitized_text ILIKE :deterministic_phrase"
                ),
                {
                    "run_id": run_id,
                    "deterministic_phrase": "%signal extracted in deterministic test mode%",
                },
            ).scalar_one()
            latest_report_row = connection.execute(
                text(
                    "SELECT content_json, content_markdown FROM reports "
                    "WHERE run_id = :run_id ORDER BY created_at DESC LIMIT 1"
                ),
                {"run_id": run_id},
            ).mappings().first()
            skill_candidate_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM skill_candidates "
                    "WHERE supporting_run_ids @> CAST(:supporting_run_ids AS jsonb)"
                ),
                {"supporting_run_ids": f'["{run_id}"]'},
            ).scalar_one()
            skill_candidate_staging_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM skill_candidates "
                    "WHERE status = 'staging' "
                    "AND supporting_run_ids @> CAST(:supporting_run_ids AS jsonb)"
                ),
                {"supporting_run_ids": f'["{run_id}"]'},
            ).scalar_one()
            conclusion_count = connection.execute(
                text("SELECT COUNT(*) AS count FROM conclusions WHERE run_id = :run_id"),
                {"run_id": run_id},
            ).scalar_one()
            conclusion_evidence_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM conclusion_evidence ce "
                    "JOIN conclusions c ON c.conclusion_id = ce.conclusion_id "
                    "WHERE c.run_id = :run_id"
                ),
                {"run_id": run_id},
            ).scalar_one()
            comparison_cell_count = connection.execute(
                text("SELECT COUNT(*) AS count FROM comparison_cells WHERE run_id = :run_id"),
                {"run_id": run_id},
            ).scalar_one()
    finally:
        engine.dispose()

    if latest_report_row is not None:
        report_json_raw = latest_report_row["content_json"]
        report_markdown_raw = latest_report_row["content_markdown"]
        if isinstance(report_json_raw, dict):
            sections_raw = report_json_raw.get("sections")
        else:
            sections_raw = []
        if isinstance(sections_raw, list):
            report_sections_content_count = len(
                [
                    section
                    for section in sections_raw
                    if isinstance(section, dict)
                    and isinstance(section.get("content_markdown"), str)
                    and bool(section["content_markdown"].strip())
                ]
            )
        else:
            report_sections_content_count = 0
        if isinstance(report_markdown_raw, str):
            report_has_evidence_citation = "[ev_" in report_markdown_raw
        else:
            report_has_evidence_citation = False
    else:
        report_sections_content_count = 0
        report_has_evidence_citation = False

    return {
        "run_status": run_row["status"] if run_row else "missing",
        "step_count": int(step_count),
        "first_tool": first_decision_row["chosen_tool"] if first_decision_row else "missing",
        "latest_tool": decision_row["chosen_tool"] if decision_row else "missing",
        "qa_step_count": int(qa_step_count),
        "qa_rejection_count": int(qa_rejection_count),
        "supervisor_step_count": int(supervisor_step_count),
        "analyst_step_count": int(analyst_step_count),
        "supervisor_llm_call_count": int(supervisor_llm_call_count),
        "supervisor_llm_success_count": int(supervisor_llm_success_count),
        "supervisor_llm_prompt_hash_count": int(supervisor_llm_prompt_hash_count),
        "analyst_llm_call_count": int(analyst_llm_call_count),
        "analyst_llm_summarization_slot_count": int(analyst_llm_summarization_slot_count),
        "analyst_fallback_mode_count": int(analyst_fallback_mode_count),
        "qa_llm_call_count": int(qa_llm_call_count),
        "qa_semantic_degraded_count": int(qa_semantic_degraded_count),
        "writer_llm_call_count": int(writer_llm_call_count),
        "researcher_llm_call_count": int(researcher_llm_call_count),
        "researcher_llm_research_slot_count": int(researcher_llm_research_slot_count),
        "researcher_step_count": int(researcher_step_count),
        "researcher_started_span_seconds": float(researcher_started_span_seconds_raw),
        "checkpoint_row_count": int(checkpoint_row_count),
        "checkpoint_writes_row_count": int(checkpoint_writes_row_count),
        "evidence_count": int(evidence_count),
        "structured_evidence_count": int(structured_evidence_count),
        "evidence_url_count": int(evidence_url_count),
        "expected_phrase_count": int(expected_phrase_count),
        "report_sections_content_count": int(report_sections_content_count),
        "report_has_evidence_citation": report_has_evidence_citation,
        "skill_candidate_count": int(skill_candidate_count),
        "skill_candidate_staging_count": int(skill_candidate_staging_count),
        "conclusion_count": int(conclusion_count),
        "conclusion_evidence_count": int(conclusion_evidence_count),
        "comparison_cell_count": int(comparison_cell_count),
    }


def _extract_report_evidence_refs(content_json: object) -> set[str]:
    if not isinstance(content_json, dict):
        return set()
    sections_raw = content_json.get("sections")
    if not isinstance(sections_raw, list):
        return set()
    refs: set[str] = set()
    for section in sections_raw:
        if not isinstance(section, dict):
            continue
        evidence_refs_raw = section.get("evidence_refs")
        if not isinstance(evidence_refs_raw, list):
            continue
        for item in evidence_refs_raw:
            if isinstance(item, str):
                refs.add(item)
    return refs


def _fetch_latest_report_evidence_refs(run_id: str) -> set[str]:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.connect() as connection:
            latest_report_row = connection.execute(
                text(
                    "SELECT content_json FROM reports "
                    "WHERE run_id = :run_id ORDER BY created_at DESC LIMIT 1"
                ),
                {"run_id": run_id},
            ).mappings().first()
    finally:
        engine.dispose()

    if latest_report_row is None:
        return set()
    return _extract_report_evidence_refs(latest_report_row["content_json"])


def _assert_trace_payload_omits_raw_llm_content(value: object) -> None:
    if isinstance(value, dict):
        assert "prompt_text" not in value
        assert "response_raw" not in value
        for nested in value.values():
            _assert_trace_payload_omits_raw_llm_content(nested)
        return
    if isinstance(value, list):
        for nested in value:
            _assert_trace_payload_omits_raw_llm_content(nested)


def test_health_endpoint(test_client: TestClient) -> None:
    response = test_client.get("/health")
    payload = response.json()
    assert response.status_code == 200
    assert payload["status"] == "ok"


def test_create_run_persists_rows(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs",
        json={
            "user_query": "compare cursor and windsurf for founders",
            "competitors": ["comp_cursor", "comp_windsurf"],
            "domain_hint": "ai coding assistants",
            "reference_urls": ["https://cursor.com/pricing"],
            "target_roles": ["pm", "founder"],
        },
    )
    payload = response.json()
    assert response.status_code == 200
    assert payload["status"] == "running"
    assert payload["run_id"].startswith("run_")
    assert _wait_for_run_terminal(payload["run_id"]) == "completed"
    assert _wait_for_skill_candidate_count(payload["run_id"]) >= 1

    snapshot = _fetch_persisted_snapshot(payload["run_id"])
    assert snapshot["run_status"] == "completed"
    assert snapshot["step_count"] >= 5
    assert snapshot["first_tool"] == "ConductResearchBatch"
    assert snapshot["latest_tool"] == "Write"
    assert snapshot["qa_step_count"] >= 1
    assert snapshot["qa_rejection_count"] == 0
    assert snapshot["supervisor_llm_call_count"] >= snapshot["supervisor_step_count"]
    assert snapshot["supervisor_llm_success_count"] >= 1
    assert snapshot["supervisor_llm_prompt_hash_count"] >= 1
    assert snapshot["analyst_step_count"] >= 1
    assert snapshot["analyst_llm_call_count"] >= 1
    assert snapshot["analyst_llm_summarization_slot_count"] >= 1
    assert snapshot["qa_llm_call_count"] >= 1
    assert snapshot["qa_semantic_degraded_count"] >= 1
    assert snapshot["writer_llm_call_count"] >= 1
    assert snapshot["researcher_llm_call_count"] >= 1
    assert snapshot["researcher_llm_research_slot_count"] >= 1
    assert snapshot["researcher_step_count"] >= 2
    assert snapshot["researcher_started_span_seconds"] < 10.0
    assert snapshot["checkpoint_row_count"] >= 1
    assert snapshot["checkpoint_writes_row_count"] >= 1
    assert snapshot["evidence_count"] >= 1
    assert snapshot["structured_evidence_count"] >= 1 or snapshot["evidence_url_count"] >= 1
    assert snapshot["evidence_url_count"] >= 1
    assert snapshot["expected_phrase_count"] >= 1
    assert snapshot["report_sections_content_count"] >= 3
    assert snapshot["report_has_evidence_citation"] is True
    assert snapshot["skill_candidate_count"] >= 1
    assert snapshot["skill_candidate_staging_count"] >= 1
    assert snapshot["conclusion_count"] >= 1
    assert snapshot["conclusion_evidence_count"] >= snapshot["conclusion_count"]
    assert snapshot["comparison_cell_count"] >= 2


def test_get_run_detail_and_trace(test_client: TestClient) -> None:
    create_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "what is the pricing differentiation",
            "competitors": ["comp_cursor"],
            "domain_hint": "ai coding assistants",
            "reference_urls": ["https://cursor.com/pricing"],
            "target_roles": ["pm"],
        },
    )
    assert create_response.status_code == 200
    run_id = create_response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) == "completed"

    detail_response = test_client.get(f"/api/runs/{run_id}")
    assert detail_response.status_code == 200
    detail_payload = detail_response.json()
    assert detail_payload["run_id"] == run_id
    assert detail_payload["status"] == "completed"
    assert detail_payload["domain_hint"] == "ai coding assistants"
    assert detail_payload["reference_urls"] == ["https://cursor.com/pricing"]
    assert detail_payload["user_query"] == "what is the pricing differentiation"

    trace_response = test_client.get(f"/api/runs/{run_id}/trace")
    assert trace_response.status_code == 200
    assert "charset=utf-8" in trace_response.headers["content-type"].lower()
    trace_payload = trace_response.json()
    assert trace_payload["run"]["run_id"] == run_id
    assert len(trace_payload["steps"]) >= 4
    assert len(trace_payload["supervisor_decisions"]) >= 3
    assert len(trace_payload["llm_calls"]) >= 1
    assert len(trace_payload["timeline"]) >= (
        len(trace_payload["steps"])
        + len(trace_payload["supervisor_decisions"])
        + len(trace_payload["llm_calls"])
    )
    timeline_timestamps = [
        datetime.fromisoformat(item["timestamp"]) for item in trace_payload["timeline"]
    ]
    assert timeline_timestamps == sorted(timeline_timestamps)
    timeline_kinds = {item["kind"] for item in trace_payload["timeline"]}
    assert {"step", "decision", "llm_call"}.issubset(timeline_kinds)
    llm_call = trace_payload["llm_calls"][0]
    assert {
        "id",
        "step_id",
        "model_slot",
        "provider",
        "model_name",
        "prompt_hash",
        "prompt_preview",
        "prompt_tokens",
        "completion_tokens",
        "latency_ms",
        "error",
            "fallback_used",
            "fallback_reason",
            "retry_count",
            "created_at",
        } == set(llm_call.keys())
    _assert_trace_payload_omits_raw_llm_content(trace_payload)
    decision_tools = [item["chosen_tool"] for item in trace_payload["supervisor_decisions"]]
    step_agents = [item["agent_name"] for item in trace_payload["steps"]]
    assert decision_tools[-1] == "Write"
    assert "ConductResearch" in decision_tools
    assert "Analyze" in decision_tools
    assert "Write" in decision_tools
    assert "researcher" in step_agents
    assert "analyst" in step_agents
    assert "writer" in step_agents
    assert "qa" in step_agents
    # The curator runs out-of-band after run.finish (and now writes its own step once
    # the async run settles), so assert against the supervisor decision loop instead:
    # the supervisor must never route to the curator as a tool.
    assert "skill_curator" not in decision_tools
    assert _wait_for_skill_candidate_count(run_id) >= 1

    not_found_response = test_client.get("/api/runs/run_not_exists")
    assert not_found_response.status_code == 404
    assert not_found_response.json()["error_code"] == "RUN_NOT_FOUND"


def test_get_run_integration_endpoints(test_client: TestClient) -> None:
    create_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "integration endpoints check",
            "competitors": ["comp_cursor", "comp_windsurf"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert create_response.status_code == 200
    run_id = create_response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) == "completed"

    list_response = test_client.get("/api/runs", params={"status": "completed", "limit": 20, "offset": 0})
    assert list_response.status_code == 200
    list_payload = list_response.json()
    assert isinstance(list_payload["items"], list)
    run_items = [item for item in list_payload["items"] if item["run_id"] == run_id]
    assert run_items
    listed_run = run_items[0]
    assert listed_run["step_count"] >= 1
    assert listed_run["evidence_count"] >= 1
    assert listed_run["has_report"] is True

    report_response = test_client.get(f"/api/runs/{run_id}/report")
    assert report_response.status_code == 200
    assert "charset=utf-8" in report_response.headers["content-type"].lower()
    report_payload = report_response.json()
    assert report_payload["run_id"] == run_id
    assert isinstance(report_payload["content_markdown"], str)
    assert report_payload["content_markdown"].strip()
    assert isinstance(report_payload["evidence_id_to_brief"], dict)
    assert report_payload["evidence_id_to_brief"]

    evidence_response = test_client.get(f"/api/runs/{run_id}/evidence")
    assert evidence_response.status_code == 200
    assert "charset=utf-8" in evidence_response.headers["content-type"].lower()
    evidence_payload = evidence_response.json()
    assert isinstance(evidence_payload, list)
    assert evidence_payload
    assert all(item["run_id"] == run_id for item in evidence_payload)

    competitor_filtered_response = test_client.get(
        f"/api/runs/{run_id}/evidence",
        params={"competitor_id": "comp_cursor"},
    )
    assert competitor_filtered_response.status_code == 200
    competitor_filtered_payload = competitor_filtered_response.json()
    assert competitor_filtered_payload
    assert all(item["competitor_id"] == "comp_cursor" for item in competitor_filtered_payload)

    source_type_filtered_response = test_client.get(
        f"/api/runs/{run_id}/evidence",
        params={"source_type": "article"},
    )
    assert source_type_filtered_response.status_code == 200
    source_type_filtered_payload = source_type_filtered_response.json()
    if source_type_filtered_payload:
        assert all(item["source_type"] == "article" for item in source_type_filtered_payload)

    conclusions_response = test_client.get(f"/api/runs/{run_id}/conclusions")
    assert conclusions_response.status_code == 200
    conclusions_payload = conclusions_response.json()
    assert conclusions_payload["run_id"] == run_id
    assert isinstance(conclusions_payload["items"], list)
    assert conclusions_payload["items"]
    assert all(item["run_id"] == run_id for item in conclusions_payload["items"])
    assert all(isinstance(item["evidence_ids"], list) and item["evidence_ids"] for item in conclusions_payload["items"])

    comparisons_response = test_client.get(f"/api/runs/{run_id}/comparisons")
    assert comparisons_response.status_code == 200
    comparisons_payload = comparisons_response.json()
    assert comparisons_payload["run_id"] == run_id
    assert isinstance(comparisons_payload["items"], list)
    assert comparisons_payload["items"]
    first_comparison = comparisons_payload["items"][0]
    assert isinstance(first_comparison["cells"], list)
    assert len(first_comparison["cells"]) >= 2
    assert {cell["stance"] for cell in first_comparison["cells"]}.issubset(
        {"leader", "competitive", "laggard", "unknown"}
    )

    packs_response = test_client.get("/api/demo-fixtures/competitors")
    assert packs_response.status_code == 200
    packs_payload = packs_response.json()
    assert isinstance(packs_payload, list)
    competitor_ids = {item["id"] for item in packs_payload if isinstance(item, dict) and isinstance(item.get("id"), str)}
    if competitor_ids:
        assert {"comp_cursor", "comp_windsurf"}.issubset(competitor_ids)


@pytest.mark.asyncio
async def test_writer_report_evidence_refs_stable_with_table_toggle(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    if "postgresql+psycopg://" in settings.DATABASE_URL:
        async_database_url = settings.DATABASE_URL.replace(
            "postgresql+psycopg://",
            "postgresql+asyncpg://",
        )
        monkeypatch.setattr(settings, "DATABASE_URL", async_database_url)
    run_id = f"run_writer_toggle_{uuid4().hex[:8]}"
    step_id = f"step_writer_toggle_{uuid4().hex[:8]}"
    engine = create_async_engine(settings.DATABASE_URL, pool_pre_ping=True)
    session_factory = async_sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    now = datetime.now(timezone.utc)
    evidence_rows = [
        EvidenceRecord(
            id=f"ev_toggle_{uuid4().hex[:8]}",
            run_id=run_id,
            source_type="article",
            source_url="https://example.com/cursor-feature",
            source_title="cursor feature",
            quote="Cursor feature evidence.",
            sanitized_text="Cursor feature evidence.",
            span={"dimension": "feature", "competitor_id": "comp_cursor"},
            collected_by=step_id,
            collected_at=now,
            desensitized=True,
        ),
        EvidenceRecord(
            id=f"ev_toggle_{uuid4().hex[:8]}",
            run_id=run_id,
            source_type="article",
            source_url="https://example.com/windsurf-pricing",
            source_title="windsurf pricing",
            quote="Windsurf pricing evidence.",
            sanitized_text="Windsurf pricing evidence.",
            span={"dimension": "pricing", "competitor_id": "comp_windsurf"},
            collected_by=step_id,
            collected_at=now,
            desensitized=True,
        ),
    ]
    analysis_payload = {
        "summary": "toggle test analyst summary",
        "insights": [
            {
                "dimension": "feature",
                "finding": "Cursor keeps stronger project memory.",
                "confidence": "high",
                "evidence_ids": [evidence_rows[0].id],
            },
            {
                "dimension": "pricing",
                "finding": "Windsurf has lower starter tier.",
                "confidence": "medium",
                "evidence_ids": [evidence_rows[1].id],
            }
        ],
        "risk_flags": ["feature_gap", "pricing_volatility"],
        "recommended_sections": ["feature", "pricing"],
    }

    try:
        async with session_factory() as session:
            session.add(
                Run(
                    run_id=run_id,
                    user_query="writer conclusions toggle comparison",
                    domain_hint="ai coding assistants",
                    reference_urls=[],
                    status="running",
                    target_roles=["pm"],
                    competitors=["comp_cursor", "comp_windsurf"],
                )
            )
            session.add(
                Step(
                    step_id=step_id,
                    run_id=run_id,
                    agent_name="analyst",
                    status="completed",
                    retry_count=0,
                    payload={"analysis_payload": analysis_payload},
                )
            )
            await session.flush()
            for row in evidence_rows:
                session.add(row)
            await session.flush()
            await persist_conclusions_for_step(
                session=session,
                run_id=run_id,
                step_id=step_id,
                insights=analysis_payload["insights"],
                evidence_lookup={row.id: row for row in evidence_rows},
                risk_flags=analysis_payload["risk_flags"],
            )
            await session.commit()

        monkeypatch.setattr("agents.nodes.writer.get_session_factory", lambda: session_factory)
        monkeypatch.setattr(settings, "WRITER_READ_CONCLUSIONS_FROM_TABLE", True)
        await writer_node(
            {
                "run_id": run_id,
                "user_query": "writer conclusions toggle comparison",
                "competitors": ["comp_cursor", "comp_windsurf"],
                "pending_tool_args": {
                    "template_id": "battlecard_default",
                    "sections": ["feature", "pricing"],
                },
            }
        )
        enabled_refs = _fetch_latest_report_evidence_refs(run_id)
        assert enabled_refs

        monkeypatch.setattr(settings, "WRITER_READ_CONCLUSIONS_FROM_TABLE", False)
        await writer_node(
            {
                "run_id": run_id,
                "user_query": "writer conclusions toggle comparison",
                "competitors": ["comp_cursor", "comp_windsurf"],
                "pending_tool_args": {
                    "template_id": "battlecard_default",
                    "sections": ["feature", "pricing"],
                },
            }
        )
        disabled_refs = _fetch_latest_report_evidence_refs(run_id)
        assert disabled_refs == enabled_refs

        async with session_factory() as session:
            await session.execute(delete(Run).where(Run.run_id == run_id))
            await session.commit()
    finally:
        await engine.dispose()


def test_resume_run_continues_from_checkpoint(test_client: TestClient) -> None:
    create_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "resume from checkpoint",
            "competitors": ["comp_cursor", "comp_windsurf"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert create_response.status_code == 200
    run_id = create_response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) == "completed"

    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE runs SET status = 'running', finished_at = :finished_at "
                    "WHERE run_id = :run_id"
                ),
                {"run_id": run_id, "finished_at": None},
            )
    finally:
        engine.dispose()

    resume_response = test_client.post(f"/api/runs/{run_id}/resume")
    assert resume_response.status_code == 200
    resume_payload = resume_response.json()
    assert resume_payload["run_id"] == run_id
    assert resume_payload["status"] == "completed"

    detail_response = test_client.get(f"/api/runs/{run_id}")
    assert detail_response.status_code == 200
    detail_payload = detail_response.json()
    assert detail_payload["status"] == "completed"
    assert detail_payload["finished_at"] is not None
    assert _wait_for_skill_candidate_count(run_id) >= 1

    non_resumable_response = test_client.post(f"/api/runs/{run_id}/resume")
    assert non_resumable_response.status_code == 409
    assert non_resumable_response.json()["error_code"] == "RUN_NOT_RESUMABLE"


def test_reset_to_writer_replays_report(test_client: TestClient) -> None:
    create_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "reset writer replay",
            "competitors": ["comp_cursor", "comp_windsurf"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert create_response.status_code == 200
    run_id = create_response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) == "completed"

    reset_response = test_client.post(
        f"/api/runs/{run_id}/reset",
        json={"reset_to": "writer"},
    )
    assert reset_response.status_code == 200
    reset_payload = reset_response.json()
    assert reset_payload["run_id"] == run_id
    assert reset_payload["status"] == "completed"

    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.connect() as connection:
            writer_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'writer'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            analyst_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'analyst'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            qa_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'qa'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            report_count = connection.execute(
                text("SELECT COUNT(*) AS count FROM reports WHERE run_id = :run_id"),
                {"run_id": run_id},
            ).scalar_one()
    finally:
        engine.dispose()

    assert int(writer_step_count) >= 1
    assert int(analyst_step_count) >= 1
    assert int(qa_step_count) >= 1
    assert int(report_count) >= 1
    assert _wait_for_skill_candidate_count(run_id) >= 1


def test_reset_to_analyst_regenerates_conclusions(test_client: TestClient) -> None:
    create_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "reset analyst replay",
            "competitors": ["comp_cursor", "comp_windsurf"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert create_response.status_code == 200
    run_id = create_response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) == "completed"

    reset_response = test_client.post(
        f"/api/runs/{run_id}/reset",
        json={"reset_to": "analyst"},
    )
    assert reset_response.status_code == 200
    reset_payload = reset_response.json()
    assert reset_payload["run_id"] == run_id
    assert reset_payload["status"] == "completed"

    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.connect() as connection:
            analyst_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'analyst'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            writer_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'writer'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            qa_step_count = connection.execute(
                text(
                    "SELECT COUNT(*) AS count FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'qa'"
                ),
                {"run_id": run_id},
            ).scalar_one()
            conclusion_count = connection.execute(
                text("SELECT COUNT(*) AS count FROM conclusions WHERE run_id = :run_id"),
                {"run_id": run_id},
            ).scalar_one()
    finally:
        engine.dispose()

    assert int(analyst_step_count) >= 1
    assert int(writer_step_count) >= 1
    assert int(qa_step_count) >= 1
    assert int(conclusion_count) >= 1
    assert _wait_for_skill_candidate_count(run_id) >= 1


def test_reset_rejects_running_run(test_client: TestClient) -> None:
    create_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "reset running run reject",
            "competitors": ["comp_cursor"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert create_response.status_code == 200
    run_id = create_response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) == "completed"

    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE runs SET status = 'running', finished_at = :finished_at "
                    "WHERE run_id = :run_id"
                ),
                {"run_id": run_id, "finished_at": None},
            )
    finally:
        engine.dispose()

    reset_response = test_client.post(
        f"/api/runs/{run_id}/reset",
        json={"reset_to": "writer"},
    )
    assert reset_response.status_code == 409
    assert reset_response.json()["error_code"] == "RUN_NOT_RESETTABLE"


def test_reset_rejects_invalid_stage(test_client: TestClient) -> None:
    create_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "reset invalid stage reject",
            "competitors": ["comp_cursor"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert create_response.status_code == 200
    run_id = create_response.json()["run_id"]

    reset_response = test_client.post(
        f"/api/runs/{run_id}/reset",
        json={"reset_to": "researcher"},
    )
    assert reset_response.status_code == 422


def test_run_events_sse_endpoint_exposes_stream_content_type(test_client: TestClient) -> None:
    create_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "sse endpoint smoke",
            "competitors": ["comp_cursor"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert create_response.status_code == 200
    run_id = create_response.json()["run_id"]
    # POST /api/runs is async (Phase 0b): the graph emits real events to the run_id
    # channel concurrently. Use a dedicated channel so the pub/sub contract check is
    # not polluted by background run events.
    sse_channel = f"{run_id}_sse_contract"

    event_bus = getattr(test_client.app.state, "event_bus", None)
    assert event_bus is not None
    subscribe_loop = asyncio.new_event_loop()
    publish_loop = asyncio.new_event_loop()
    subscribe_cm = event_bus.subscribe(sse_channel)
    try:
        queue = subscribe_loop.run_until_complete(subscribe_cm.__aenter__())
        step_id = "step_sse_contract"
        publish_loop.run_until_complete(
            event_bus.publish(
                RunEvent(
                    run_id=sse_channel,
                    event_type=RunEventType.STEP_START,
                    step_id=step_id,
                    payload={"agent_name": "supervisor"},
                )
            )
        )
        event = subscribe_loop.run_until_complete(asyncio.wait_for(queue.get(), timeout=1.0))
    finally:
        subscribe_loop.run_until_complete(subscribe_cm.__aexit__(None, None, None))
        subscribe_loop.close()
        publish_loop.close()
    assert event.event_type in {RunEventType.STEP_START, RunEventType.CURATOR_START}
    if event.event_type == RunEventType.STEP_START:
        assert event.step_id == "step_sse_contract"


def test_main_graph_no_skill_curator_node() -> None:
    graph = build_graph_uncompiled()
    assert "skill_curator" not in graph.nodes


def test_schema_models_instantiation() -> None:
    now = "2026-05-23T00:00:00+00:00"

    evidence = Evidence(
        id="ev_cursor_001",
        run_id="run_demo_001",
        source_type="official_site",
        source_url="https://cursor.com",
        source_title="Cursor",
        quote="Cursor supports repository context.",
        sanitized_text="Cursor supports repository context.",
        span={"start": 0, "end": 35},
        collected_by="step_researcher_001",
        collected_at=now,
        desensitized=True,
    )
    assert evidence.id.startswith("ev_")

    agent_message = AgentMessage(
        message_id="msg_001",
        run_id="run_demo_001",
        step_id="step_001",
        trace_id="trace_001",
        source_agent="researcher",
        target_agent="supervisor",
        status="completed",
        payload_type="evidence_batch",
        payload={"evidence_ids": ["ev_cursor_001"]},
        evidence_refs=["ev_cursor_001"],
        artifact_refs=["artifact_001"],
        created_at=now,
    )
    assert agent_message.payload_type == "evidence_batch"

    decision = SupervisorDecision(
        id="decision_001",
        run_id="run_demo_001",
        iteration=1,
        chosen_tool="Finalize",
        tool_args={"completion_reason": "user_requested_stop"},
        reasoning_summary="Skeleton run.",
        triggered_by="user_query",
        outcome="succeeded",
        outcome_recorded_at=now,
        created_at=now,
    )
    assert decision.chosen_tool == "Finalize"

    rejection = Rejection(
        rejection_id="rejection_001",
        step_id="step_qa_001",
        reject_to="researcher",
        failed_rule_ids=["rule_pricing_requires_evidence"],
        semantic_findings=["Missing official source"],
        required_fields=["pricing.evidence_ids"],
        retry_policy=RetryPolicy(max_retry=3, current_retry=1),
        severity="blocking",
        reviewer_step_id="step_qa_001",
        created_at=now,
    )
    assert rejection.retry_policy.max_retry == 3

    candidate = SkillCandidate(
        id="skill_001",
        candidate_type="qa_rule",
        applies_to="qa_rule",
        tags=["ai_coding", "pricing"],
        payload={
            "rule_yaml": (
                "id: rule_x\n"
                "when:\n"
                "  section_id_in: [pricing]\n"
                "require:\n"
                "  evidence_refs_count_gte: 1\n"
            )
        },
        rationale="Recurring QA failure pattern",
        supporting_run_ids=["run_demo_001"],
        confidence="medium",
        created_at=now,
    )
    assert candidate.status == "staging"


def test_create_run_accepts_reference_urls_as_runtime_hints(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs",
        json={
            "user_query": "reference url normalization",
            "competitors": ["comp_cursor"],
            "reference_urls": ["  not-a-valid-url  ", "", "not-a-valid-url"],
            "target_roles": ["pm"],
        },
    )
    payload = response.json()
    assert response.status_code == 200
    run_id = payload["run_id"]
    detail_response = test_client.get(f"/api/runs/{run_id}")
    assert detail_response.status_code == 200
    detail_payload = detail_response.json()
    assert detail_payload["reference_urls"] == ["not-a-valid-url"]


def test_create_run_accepts_free_competitor_names(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs",
        json={
            "user_query": "free competitor mode",
            "competitors": ["Notion", "Obsidian"],
            "domain_hint": "knowledge management and notes",
            "target_roles": ["pm"],
        },
    )
    payload = response.json()
    assert response.status_code == 200
    assert isinstance(payload["run_id"], str)


def test_run_without_pack_with_arbitrary_competitors(test_client: TestClient) -> None:
    response = test_client.post(
        "/api/runs",
        json={
            "user_query": "比较两款笔记产品的功能/定价/用户口碑",
            "competitors": ["Notion", "Obsidian"],
            "domain_hint": None,
            "target_roles": ["pm"],
        },
    )
    assert response.status_code == 200
    run_id = response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) in {"completed", "degraded"}

    report_response = test_client.get(f"/api/runs/{run_id}/report")
    assert report_response.status_code == 200
    assert "charset=utf-8" in report_response.headers["content-type"].lower()
    report_payload = report_response.json()
    content_json = report_payload.get("content_json", {})
    sections = content_json.get("sections", []) if isinstance(content_json, dict) else []
    assert isinstance(sections, list)
    assert len(sections) >= 3

    trace_response = test_client.get(f"/api/runs/{run_id}/trace")
    assert trace_response.status_code == 200
    assert "charset=utf-8" in trace_response.headers["content-type"].lower()
    trace_payload = trace_response.json()
    assert trace_payload["run"]["status"] in {"completed", "degraded"}
    qa_steps = [step for step in trace_payload["steps"] if step.get("agent_name") == "qa"]
    assert qa_steps, "expected at least one qa step"
    final_qa_outcome = qa_steps[-1].get("payload", {}).get("qa_outcome")
    assert final_qa_outcome == "approved"


def _prepare_temp_skills_root(
    *,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> Path:
    skills_root = (tmp_path / "skills").resolve()
    skills_root.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr("router.skill_rt._skills_root", lambda: skills_root)
    store = get_skill_store()
    monkeypatch.setattr(store, "skills_dir", skills_root)
    store.scan()
    return skills_root


def _write_qa_rule_skill(*, skills_root: Path, skill_id: str, rule_yaml: str) -> None:
    skill_dir = skills_root / "qa_rule" / skill_id
    skill_dir.mkdir(parents=True, exist_ok=True)
    content = (
        "---\n"
        f"name: {skill_id}\n"
        "description: Smoke-test promoted qa rule.\n"
        "version: 1.0.0\n"
        "tags:\n"
        "  - promoted\n"
        "applies_to: qa_rule\n"
        "---\n\n"
        "## Rule DSL\n\n"
        "```yaml\n"
        f"{rule_yaml.strip()}\n"
        "```\n"
    )
    (skill_dir / "SKILL.md").write_text(content, encoding="utf-8")


def _latest_staging_skill_candidate_id_for_run(run_id: str, *, timeout_seconds: float = 5.0) -> str:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        engine = create_engine(settings.DATABASE_URL_SYNC)
        try:
            with engine.connect() as connection:
                row = connection.execute(
                    text(
                        "SELECT id FROM skill_candidates "
                        "WHERE status = 'staging' "
                        "AND supporting_run_ids @> CAST(:supporting_run_ids AS jsonb) "
                        "ORDER BY created_at DESC LIMIT 1"
                    ),
                    {"supporting_run_ids": json.dumps([run_id], ensure_ascii=False)},
                ).mappings().first()
        finally:
            engine.dispose()
        if row is not None:
            return str(row["id"])
        time.sleep(0.2)
    raise RuntimeError(f"No staging skill candidate found for run_id={run_id}")


def _latest_qa_step_payload(run_id: str) -> dict[str, object]:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.connect() as connection:
            row = connection.execute(
                text(
                    "SELECT payload FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'qa' "
                    "ORDER BY created_at DESC LIMIT 1"
                ),
                {"run_id": run_id},
            ).mappings().first()
    finally:
        engine.dispose()
    if row is None:
        raise RuntimeError(f"No qa step found for run_id={run_id}")
    payload = row["payload"]
    if not isinstance(payload, dict):
        raise RuntimeError("QA payload is not a dict")
    return payload


def _qa_payloads_for_run(run_id: str) -> list[dict[str, object]]:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.connect() as connection:
            rows = connection.execute(
                text(
                    "SELECT payload FROM steps "
                    "WHERE run_id = :run_id AND agent_name = 'qa' "
                    "ORDER BY created_at ASC"
                ),
                {"run_id": run_id},
            ).mappings().all()
    finally:
        engine.dispose()
    payloads: list[dict[str, object]] = []
    for row in rows:
        payload = row["payload"]
        if isinstance(payload, dict):
            payloads.append(payload)
    return payloads


def _wait_for_skill_candidate_count(run_id: str, *, timeout_seconds: float = 5.0) -> int:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        engine = create_engine(settings.DATABASE_URL_SYNC)
        try:
            with engine.connect() as connection:
                count = connection.execute(
                    text(
                        "SELECT COUNT(*) AS count FROM skill_candidates "
                        "WHERE supporting_run_ids @> CAST(:supporting_run_ids AS jsonb)"
                    ),
                    {"supporting_run_ids": json.dumps([run_id], ensure_ascii=False)},
                ).scalar_one()
        finally:
            engine.dispose()
        normalized_count = int(count)
        if normalized_count > 0:
            return normalized_count
        time.sleep(0.2)
    return 0


_TERMINAL_RUN_STATUSES = {"completed", "degraded", "failed"}


def _wait_for_run_terminal(run_id: str, *, timeout_seconds: float = 30.0) -> str:
    """Poll the runs table until the background graph task reaches a terminal status.

    POST /api/runs is asynchronous (Phase 0b): it returns status=running immediately
    while the supervisor graph runs in an asyncio background task on the TestClient
    portal loop. Tests that read completed artifacts must wait for that task to finish.
    """
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
            if last_status in _TERMINAL_RUN_STATUSES:
                return last_status
        time.sleep(0.1)
    raise RuntimeError(
        f"run_id={run_id} did not reach a terminal status within {timeout_seconds}s (last={last_status})"
    )


def test_promoted_qa_rule_visible_in_next_run(
    test_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    _prepare_temp_skills_root(monkeypatch=monkeypatch, tmp_path=tmp_path)
    first_run = test_client.post(
        "/api/runs",
        json={
            "user_query": "generate candidate for promotion smoke",
            "competitors": ["comp_cursor"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert first_run.status_code == 200
    first_run_id = first_run.json()["run_id"]
    assert _wait_for_run_terminal(first_run_id) == "completed"
    candidate_id = _latest_staging_skill_candidate_id_for_run(first_run_id)

    approve_response = test_client.post(
        f"/api/skill-candidates/{candidate_id}/approve",
        json={"reviewed_by": "owner_wh"},
    )
    approve_payload = approve_response.json()
    assert approve_response.status_code == 200
    promoted_artifacts = approve_payload.get("promoted_artifacts", [])
    assert isinstance(promoted_artifacts, list) and promoted_artifacts

    get_skill_store().scan()
    second_run = test_client.post(
        "/api/runs",
        json={
            "user_query": "verify promoted qa rules visibility",
            "competitors": ["comp_cursor"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert second_run.status_code == 200
    second_run_id = second_run.json()["run_id"]
    assert _wait_for_run_terminal(second_run_id) in {"completed", "degraded"}
    qa_payload = _latest_qa_step_payload(second_run_id)
    promoted_rule_ids_raw = qa_payload.get("promoted_qa_rule_ids")
    assert isinstance(promoted_rule_ids_raw, list)
    promoted_rule_ids = [item for item in promoted_rule_ids_raw if isinstance(item, str)]
    assert promoted_rule_ids
    assert any(item.startswith("rule_") for item in promoted_rule_ids)


def test_promoted_qa_rule_blocks_report_with_enforced_yaml(
    test_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    skills_root = _prepare_temp_skills_root(monkeypatch=monkeypatch, tmp_path=tmp_path)
    _write_qa_rule_skill(
        skills_root=skills_root,
        skill_id="rule_pricing_requires_recent_source",
        rule_yaml=(
            "id: rule_pricing_requires_recent_source\n"
            "require:\n"
            "  has_evidence_with:\n"
            "    source_type_in: [pricing_page]\n"
            "    collected_within_days: 30\n"
            "severity: blocking\n"
            "reject_to: writer\n"
            "message: \"Pricing section must cite recent pricing evidence.\""
        ),
    )
    get_skill_store().scan()
    run_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "verify promoted qa rule enforce mode",
            "competitors": ["comp_cursor"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert run_response.status_code == 200
    run_id = run_response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) in {"completed", "degraded"}
    qa_payload = _latest_qa_step_payload(run_id)

    assert qa_payload.get("qa_outcome") in {"rejected", "force_degraded"}
    if qa_payload.get("qa_outcome") == "force_degraded":
        assert qa_payload.get("qa_reject_to") == "supervisor"
    else:
        assert qa_payload.get("reject_to") == "writer"
    failed_rule_ids_raw = qa_payload.get("failed_rule_ids")
    assert isinstance(failed_rule_ids_raw, list)
    failed_rule_ids = [item for item in failed_rule_ids_raw if isinstance(item, str)]
    assert "rule_promoted_rule_pricing_requires_recent_source" in failed_rule_ids
    blocked_rule_ids_raw = qa_payload.get("promoted_qa_blocked_rule_ids")
    assert isinstance(blocked_rule_ids_raw, list)
    blocked_rule_ids = [item for item in blocked_rule_ids_raw if isinstance(item, str)]
    assert "rule_promoted_rule_pricing_requires_recent_source" in blocked_rule_ids


def test_promoted_qa_rule_blocks_then_writer_redo_passes(
    test_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    skills_root = _prepare_temp_skills_root(monkeypatch=monkeypatch, tmp_path=tmp_path)
    _write_qa_rule_skill(
        skills_root=skills_root,
        skill_id="rule_pricing_retry_demo",
        rule_yaml=(
            "id: rule_pricing_retry_demo\n"
            "require:\n"
            "  has_evidence_with:\n"
            "    source_type_in: [pricing_page]\n"
            "    collected_within_days: 1\n"
            "severity: blocking\n"
            "reject_to: writer\n"
            "message: \"Writer must include recent pricing source.\""
        ),
    )
    get_skill_store().scan()
    run_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "promoted retry-demo source gate",
            "competitors": ["comp_cursor"],
            "domain_hint": "ai coding assistants",
            "target_roles": ["pm"],
        },
    )
    assert run_response.status_code == 200
    run_id = run_response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) in {"completed", "degraded"}
    qa_payloads = _qa_payloads_for_run(run_id)
    first_payload = qa_payloads[0]
    assert first_payload.get("qa_outcome") == "rejected"
    assert first_payload.get("reject_to") == "writer"
    failed_rule_ids_raw = first_payload.get("failed_rule_ids")
    assert isinstance(failed_rule_ids_raw, list)
    failed_rule_ids = [item for item in failed_rule_ids_raw if isinstance(item, str)]
    assert "rule_promoted_rule_pricing_retry_demo" in failed_rule_ids
