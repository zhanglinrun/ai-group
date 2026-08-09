from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from uuid import uuid4

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from models.comparison import ComparisonCellRecord
from models.conclusion import ConclusionRecord
from core.config import settings
from models.evidence import EvidenceRecord
from models.knowledge import RunKnowledgeRecord
from models.report import Report
from models.run import Run
from models.step import Step
from models.supervisor_decision import SupervisorDecisionRecord
from router.run_rt import _build_run_summary_fields
from service.metrics import RunMetricsSnapshot, build_run_metrics_snapshot

_TERMINAL_RUN_STATUSES = {"completed", "degraded", "failed"}


def test_build_run_summary_fields_uses_public_metrics_contract() -> None:
    snapshot = RunMetricsSnapshot(
        run_id="run_summary_fields",
        coverage_rate=0.5,
        evidence_count_total=3,
        evidence_count_by_competitor={"comp_cursor": 2},
        evidence_count_by_dimension={"pricing": 2},
        comparison_dimensions=["pricing"],
        conclusion_sections=[],
        report_section_ids=[],
        dimension_coverage_rate=0.5,
        evidence_dimension_coverage_rate=0.5,
        report_char_count=1234,
        report_section_count=2,
        report_depth="deep",
        report_section_coverage_rate=0.5,
        knowledge_feature_count=3,
        knowledge_pricing_count=1,
        knowledge_persona_count=1,
        knowledge_schema_coverage_rate=0.75,
        source_type_distribution={"web": 3},
        source_authority_distribution={"official": 2, "third_party": 1},
        locale_match_rate=1.0,
        locale_distribution={"global:en": 3},
        desensitization_coverage=1.0,
        qa_total_steps=2,
        qa_rejected_steps=1,
        qa_rejection_rate=0.5,
        supervisor_iterations=4,
        llm_token_total=1234,
        llm_call_count=5,
        llm_latency_p50_ms=321,
        llm_provider_error_count=1,
        llm_retry_total=2,
        manual_review_rate=0.0,
        manual_review_is_proxy=True,
        run_wall_clock_seconds=42,
    )

    fields = _build_run_summary_fields(snapshot=snapshot, status="completed")

    assert fields == {
        "status": "completed",
        "run_wall_clock_seconds": 42,
        "llm_call_count": 5,
        "llm_token_total": 1234,
        "llm_latency_p50_ms": 321,
        "coverage_rate": 0.5,
        "evidence_count_total": 3,
        "qa_rejection_rate": 0.5,
        "supervisor_iterations": 4,
    }


def _wait_for_run_terminal(run_id: str, *, timeout_seconds: float = 60.0) -> str:
    """Poll until the async POST /api/runs background graph task reaches a terminal status."""
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


def test_build_run_metrics_snapshot_reports_dimension_coverage() -> None:
    run = Run(
        run_id="run_dimension_metrics",
        user_query="dimension metrics",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_cursor"],
        plan_tree={
            "tasks": [
                {
                    "stage": "research",
                    "focus_dimensions": ["pricing", "security", "integrations"],
                }
            ]
        },
    )
    collected_at = datetime.now(timezone.utc)
    evidence_rows = [
        EvidenceRecord(
            id="ev_pricing",
            run_id=run.run_id,
            source_type="pricing_page",
            source_url="https://cursor.com/pricing",
            source_title="Cursor Pricing",
            quote="Cursor publishes pricing details.",
            sanitized_text="Cursor publishes pricing details.",
            span={
                "dimension": "pricing_page",
                "competitor_id": "comp_cursor",
                "source_authority": "official",
            },
            collected_by="step_researcher",
            collected_at=collected_at,
            desensitized=True,
        ),
        EvidenceRecord(
            id="ev_security",
            run_id=run.run_id,
            source_type="docs",
            source_url="https://cursor.com/security",
            source_title="Cursor Security",
            quote="Cursor describes security controls.",
            sanitized_text="Cursor describes security controls.",
            span={
                "dimension": "security_docs",
                "competitor_id": "comp_cursor",
                "source_authority": "third_party",
            },
            collected_by="step_researcher",
            collected_at=collected_at,
            desensitized=True,
        ),
    ]
    comparison_rows = [
        ComparisonCellRecord(
            cell_id="cmp_pricing",
            run_id=run.run_id,
            step_id="step_analyst",
            dimension="pricing",
            competitor_id="comp_cursor",
            stance="leader",
            summary="Pricing is covered.",
            evidence_ids=["ev_pricing"],
        ),
        ComparisonCellRecord(
            cell_id="cmp_security",
            run_id=run.run_id,
            step_id="step_analyst",
            dimension="security",
            competitor_id="comp_cursor",
            stance="competitive",
            summary="Security is covered.",
            evidence_ids=["ev_security"],
        ),
    ]

    snapshot = build_run_metrics_snapshot(
        run=run,
        evidence_rows=evidence_rows,
        step_rows=[],
        llm_rows=[],
        decision_rows=[],
        candidate_rows=[],
        comparison_rows=comparison_rows,
    )

    assert snapshot.evidence_count_by_dimension == {
        "integrations": 0,
        "pricing": 0,
        "pricing_page": 1,
        "security": 0,
        "security_docs": 1,
    }
    assert snapshot.comparison_dimensions == ["pricing", "security"]
    assert snapshot.dimension_coverage_rate == 2 / 3
    assert snapshot.source_authority_distribution == {"official": 1, "third_party": 1}
    assert snapshot.locale_distribution == {"global:en": 2}
    assert snapshot.locale_match_rate == 1.0


def test_build_run_metrics_snapshot_reports_low_locale_match_for_china_scope() -> None:
    run = Run(
        run_id="run_locale_metrics",
        user_query="国内竞品分析",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_a"],
        intake_draft={"market_scope": "中国大陆", "response_language": "zh"},
    )
    collected_at = datetime.now(timezone.utc)
    evidence_rows = [
        EvidenceRecord(
            id="ev_cn",
            run_id=run.run_id,
            source_type="article",
            source_url="https://example.cn/news",
            source_title="中文来源",
            quote="中文来源介绍产品能力。",
            sanitized_text="中文来源介绍产品能力。",
            span={"competitor_id": "comp_a"},
            collected_by="step_researcher",
            collected_at=collected_at,
            desensitized=True,
        ),
        EvidenceRecord(
            id="ev_en",
            run_id=run.run_id,
            source_type="article",
            source_url="https://example.com/news",
            source_title="English source",
            quote="English source discusses product capabilities.",
            sanitized_text="English source discusses product capabilities.",
            span={"competitor_id": "comp_a"},
            collected_by="step_researcher",
            collected_at=collected_at,
            desensitized=True,
        ),
    ]

    snapshot = build_run_metrics_snapshot(
        run=run,
        evidence_rows=evidence_rows,
        step_rows=[],
        llm_rows=[],
        decision_rows=[],
        candidate_rows=[],
    )

    assert snapshot.locale_distribution == {"china:zh": 1, "global:en": 1}
    assert snapshot.locale_match_rate == 0.5


def test_build_run_metrics_snapshot_uses_downstream_focus_dimensions_for_coverage() -> None:
    run = Run(
        run_id="run_downstream_dimension_metrics",
        user_query="dimension metrics",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_cursor"],
        plan_tree={
            "tasks": [
                {
                    "stage": "research",
                    "focus_dimensions": ["scene_matching_degree", "pricing", "implementation"],
                }
            ]
        },
    )
    collected_at = datetime.now(timezone.utc)
    evidence_rows = [
        EvidenceRecord(
            id="ev_raw_taxonomy",
            run_id=run.run_id,
            source_type="docs",
            source_url="https://example.com",
            source_title="Raw Taxonomy",
            quote="Raw collection dimension.",
            sanitized_text="Raw collection dimension.",
            span={"dimension": "feature", "competitor_id": "comp_cursor"},
            collected_by="step_researcher",
            collected_at=collected_at,
            desensitized=True,
        )
    ]
    comparison_rows = [
        ComparisonCellRecord(
            cell_id="cmp_scene",
            run_id=run.run_id,
            step_id="step_analyst",
            dimension="scene_matching_degree",
            competitor_id="comp_cursor",
            stance="leader",
            summary="Scene matching covered.",
            evidence_ids=["ev_raw_taxonomy"],
        )
    ]
    conclusion_rows = [
        ConclusionRecord(
            conclusion_id="concl_pricing",
            run_id=run.run_id,
            step_id="step_analyst",
            section="pricing",
            claim="Pricing covered.",
            confidence="medium",
            competitor_ids=["comp_cursor"],
            risk_flags=[],
        )
    ]
    report = Report(
        report_id="report_downstream",
        run_id=run.run_id,
        status="completed",
        content_markdown="report",
        content_json={
            "sections": [
                {"section_id": "implementation", "content_markdown": "Implementation covered."}
            ]
        },
    )

    snapshot = build_run_metrics_snapshot(
        run=run,
        evidence_rows=evidence_rows,
        step_rows=[],
        llm_rows=[],
        decision_rows=[],
        candidate_rows=[],
        report_rows=[report],
        comparison_rows=comparison_rows,
        conclusion_rows=conclusion_rows,
    )

    assert snapshot.evidence_count_by_dimension == {
        "feature": 1,
        "implementation": 0,
        "pricing": 0,
        "scene_matching_degree": 0,
    }
    assert snapshot.comparison_dimensions == ["scene_matching_degree"]
    assert snapshot.conclusion_sections == ["pricing"]
    assert snapshot.report_section_ids == ["implementation"]
    assert snapshot.dimension_coverage_rate == 1.0
    # R9: downstream coverage is 1.0, but no research dimension has on-dimension
    # evidence — the honest evidence-grounded rate must expose that (0/3).
    assert snapshot.evidence_dimension_coverage_rate == 0.0


def test_build_run_metrics_snapshot_reports_report_quality_fields() -> None:
    run = Run(
        run_id="run_report_metrics",
        user_query="report metrics",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_cursor"],
        intake_draft={"report_depth": "deep", "focus_dimensions": ["pricing", "security"]},
        plan_tree={
            "tasks": [
                {
                    "stage": "research",
                    "focus_dimensions": ["pricing", "security"],
                }
            ]
        },
    )
    report = Report(
        report_id="report_metrics",
        run_id=run.run_id,
        status="completed",
        content_markdown="x" * 3200,
        content_json={
            "sections": [
                {"section_id": "pricing", "content_markdown": "x" * 500},
            ]
        },
    )
    writer_step = Step(
        step_id="step_writer_report_metrics",
        run_id=run.run_id,
        agent_name="writer",
        status="completed",
        retry_count=0,
        payload={"target_sections": ["pricing", "security"]},
    )

    snapshot = build_run_metrics_snapshot(
        run=run,
        evidence_rows=[],
        step_rows=[writer_step],
        llm_rows=[],
        decision_rows=[],
        candidate_rows=[],
        report_rows=[report],
    )

    assert snapshot.report_char_count == 3200
    assert snapshot.report_section_count == 1
    assert snapshot.report_section_coverage_rate == 0.5
    assert snapshot.report_depth == "deep"


def test_build_run_metrics_snapshot_counts_top_level_executive_summary_coverage() -> None:
    run = Run(
        run_id="run_report_metrics_summary",
        user_query="report metrics",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_cursor"],
        intake_draft={"report_depth": "deep"},
        plan_tree=None,
    )
    report = Report(
        report_id="report_metrics_summary",
        run_id=run.run_id,
        status="completed",
        content_markdown="x" * 3200,
        content_json={
            "executive_summary": "Executive summary grounded in evidence.",
            "sections": [
                {"section_id": "pricing", "content_markdown": "x" * 500},
            ],
        },
    )
    writer_step = Step(
        step_id="step_writer_report_metrics_summary",
        run_id=run.run_id,
        agent_name="writer",
        status="completed",
        retry_count=0,
        payload={"target_sections": ["executive_summary", "pricing"]},
    )

    snapshot = build_run_metrics_snapshot(
        run=run,
        evidence_rows=[],
        step_rows=[writer_step],
        llm_rows=[],
        decision_rows=[],
        candidate_rows=[],
        report_rows=[report],
    )

    assert snapshot.report_section_count == 2
    assert snapshot.report_section_coverage_rate == 1.0
    assert "executive_summary" in snapshot.report_section_ids


def test_build_run_metrics_snapshot_reports_knowledge_triplet_metrics() -> None:
    run = Run(
        run_id="run_knowledge_metrics",
        user_query="knowledge metrics",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_cursor", "comp_windsurf"],
    )
    knowledge = RunKnowledgeRecord(
        knowledge_id="knowledge_metrics_1",
        run_id=run.run_id,
        step_id="step_analyst",
        schema_version="schema_v0.2",
        features=[
            {
                "id": "feat_1",
                "competitor_id": "comp_cursor",
                "name": "Repo context",
                "evidence_ids": ["ev_1"],
            },
            {
                "id": "feat_2",
                "competitor_id": "comp_windsurf",
                "name": "Collaboration",
                "evidence_ids": ["ev_2"],
            },
        ],
        pricings=[
            {
                "id": "price_1",
                "competitor_id": "comp_cursor",
                "model": "subscription",
                "evidence_ids": ["ev_3"],
            }
        ],
        personas=[
            {
                "id": "persona_1",
                "name": "Engineering manager",
                "role": "engineering_manager",
                "pain_points": ["review load"],
                "jobs_to_be_done": ["delivery acceleration"],
                "evidence_ids": ["ev_4"],
            }
        ],
        coverage={
            "comp_cursor": {
                "feature": "complete",
                "pricing": "complete",
                "feedback": "partial",
            },
            "comp_windsurf": {
                "feature": "partial",
                "pricing": "insufficient_data",
                "feedback": "missing",
            },
        },
    )

    snapshot = build_run_metrics_snapshot(
        run=run,
        evidence_rows=[],
        step_rows=[],
        llm_rows=[],
        decision_rows=[],
        candidate_rows=[],
        knowledge_rows=[knowledge],
    )

    assert snapshot.knowledge_feature_count == 2
    assert snapshot.knowledge_pricing_count == 1
    assert snapshot.knowledge_persona_count == 1
    assert snapshot.knowledge_schema_coverage_rate == 2.5 / 6


def test_build_run_metrics_snapshot_uses_supervisor_dimensions_without_plan_tree() -> None:
    run = Run(
        run_id="run_decision_dimension_metrics",
        user_query="dimension metrics",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_cursor"],
        plan_tree=None,
    )
    collected_at = datetime.now(timezone.utc)
    evidence_rows = [
        EvidenceRecord(
            id="ev_pricing",
            run_id=run.run_id,
            source_type="pricing_page",
            source_url="https://cursor.com/pricing",
            source_title="Cursor Pricing",
            quote="Cursor publishes pricing details.",
            sanitized_text="Cursor publishes pricing details.",
            span={"dimension": "pricing", "competitor_id": "comp_cursor"},
            collected_by="step_researcher",
            collected_at=collected_at,
            desensitized=True,
        )
    ]
    decision_rows = [
        SupervisorDecisionRecord(
            id="decision_dimensions",
            run_id=run.run_id,
            iteration=1,
            chosen_tool="ConductResearchBatch",
            tool_args={
                "topics": [
                    {
                        "competitor_id": "Cursor",
                        "focus_dimensions": ["pricing", "security"],
                    }
                ]
            },
            reasoning_summary="batch",
        )
    ]

    snapshot = build_run_metrics_snapshot(
        run=run,
        evidence_rows=evidence_rows,
        step_rows=[],
        llm_rows=[],
        decision_rows=decision_rows,
        candidate_rows=[],
    )

    assert snapshot.evidence_count_by_dimension == {"pricing": 1, "security": 0}
    assert snapshot.dimension_coverage_rate == 0.0


def test_get_run_metrics_for_completed_run(test_client: TestClient) -> None:
    create_response = test_client.post(
        "/api/runs",
        json={
            "user_query": "metrics endpoint smoke",
            "competitors": ["comp_cursor", "comp_windsurf"],
            "domain_hint": "ai coding assistants",
            "reference_urls": ["https://cursor.com/pricing"],
            "target_roles": ["pm"],
        },
    )
    assert create_response.status_code == 200
    run_id = create_response.json()["run_id"]
    assert _wait_for_run_terminal(run_id) == "completed"

    metrics_response = test_client.get(f"/api/runs/{run_id}/metrics")
    payload = metrics_response.json()
    assert metrics_response.status_code == 200
    assert payload["run_id"] == run_id

    assert 0.0 <= payload["coverage_rate"] <= 1.0
    assert payload["evidence_count_total"] >= 1
    assert set(payload["evidence_count_by_competitor"].keys()) >= {"comp_cursor", "comp_windsurf"}
    assert isinstance(payload["evidence_count_by_dimension"], dict)
    assert isinstance(payload["comparison_dimensions"], list)
    assert isinstance(payload["conclusion_sections"], list)
    assert isinstance(payload["report_section_ids"], list)
    assert 0.0 <= payload["dimension_coverage_rate"] <= 1.0
    assert payload["report_char_count"] >= 0
    assert payload["report_section_count"] >= 0
    assert payload["report_depth"] in {"debug", "quick", "deep"}
    assert 0.0 <= payload["report_section_coverage_rate"] <= 1.0
    assert payload["knowledge_feature_count"] >= 0
    assert payload["knowledge_pricing_count"] >= 0
    assert payload["knowledge_persona_count"] >= 0
    assert 0.0 <= payload["knowledge_schema_coverage_rate"] <= 1.0
    assert isinstance(payload["source_type_distribution"], dict)
    assert payload["source_type_distribution"]
    assert isinstance(payload["source_authority_distribution"], dict)
    assert 0.0 <= payload["locale_match_rate"] <= 1.0
    assert isinstance(payload["locale_distribution"], dict)
    assert 0.0 <= payload["desensitization_coverage"] <= 1.0

    assert payload["qa_total_steps"] >= 1
    assert 0 <= payload["qa_rejected_steps"] <= payload["qa_total_steps"]
    assert 0.0 <= payload["qa_rejection_rate"] <= 1.0

    assert payload["supervisor_iterations"] >= 1
    assert payload["llm_token_total"] >= 0
    assert payload["llm_call_count"] >= 1
    assert payload["llm_latency_p50_ms"] is None or payload["llm_latency_p50_ms"] >= 0
    assert payload["llm_provider_error_count"] >= 0
    assert payload["llm_retry_total"] >= 0

    assert payload["manual_review_is_proxy"] is True
    assert 0.0 <= payload["manual_review_rate"] <= 1.0
    assert payload["run_wall_clock_seconds"] is None or payload["run_wall_clock_seconds"] >= 0


def test_get_run_metrics_for_empty_run(test_client: TestClient) -> None:
    run_id = f"run_metrics_empty_{uuid4().hex[:8]}"
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO runs (run_id, user_query, domain_hint, reference_urls, status, target_roles, competitors) "
                    "VALUES (:run_id, :user_query, :domain_hint, CAST(:reference_urls AS jsonb), :status, "
                    "CAST(:target_roles AS jsonb), CAST(:competitors AS jsonb))"
                ),
                {
                    "run_id": run_id,
                    "user_query": "empty run for metrics boundary",
                    "domain_hint": "",
                    "reference_urls": json.dumps([]),
                    "status": "running",
                    "target_roles": json.dumps(["pm"]),
                    "competitors": json.dumps(["comp_cursor"]),
                },
            )

        metrics_response = test_client.get(f"/api/runs/{run_id}/metrics")
        payload = metrics_response.json()
        assert metrics_response.status_code == 200
        assert payload["run_id"] == run_id
        assert payload["coverage_rate"] == 0.0
        assert payload["evidence_count_total"] == 0
        assert payload["evidence_count_by_competitor"] == {"comp_cursor": 0}
        assert payload["evidence_count_by_dimension"] == {}
        assert payload["comparison_dimensions"] == []
        assert payload["conclusion_sections"] == []
        assert payload["report_section_ids"] == []
        assert payload["dimension_coverage_rate"] == 0.0
        assert payload["report_char_count"] == 0
        assert payload["report_section_count"] == 0
        assert payload["report_depth"] == "quick"
        assert payload["report_section_coverage_rate"] == 0.0
        assert payload["knowledge_feature_count"] == 0
        assert payload["knowledge_pricing_count"] == 0
        assert payload["knowledge_persona_count"] == 0
        assert payload["knowledge_schema_coverage_rate"] == 0.0
        assert payload["source_type_distribution"] == {}
        assert payload["source_authority_distribution"] == {}
        assert payload["locale_match_rate"] == 0.0
        assert payload["locale_distribution"] == {}
        assert payload["desensitization_coverage"] == 0.0
        assert payload["qa_total_steps"] == 0
        assert payload["qa_rejected_steps"] == 0
        assert payload["qa_rejection_rate"] == 0.0
        assert payload["supervisor_iterations"] == 0
        assert payload["llm_token_total"] == 0
        assert payload["llm_call_count"] == 0
        assert payload["llm_latency_p50_ms"] is None
        assert payload["llm_provider_error_count"] == 0
        assert payload["llm_retry_total"] == 0
        assert payload["manual_review_rate"] == 0.0
        assert payload["manual_review_is_proxy"] is True
        assert payload["run_wall_clock_seconds"] is None
    finally:
        with engine.begin() as connection:
            connection.execute(text("DELETE FROM runs WHERE run_id = :run_id"), {"run_id": run_id})
        engine.dispose()


def test_build_run_metrics_snapshot_counts_evidence_floor_rows() -> None:
    run = Run(
        run_id="run_floor_metrics",
        user_query="floor metrics",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_cursor", "comp_copilot"],
        plan_tree={
            "tasks": [
                {
                    "stage": "research",
                    "focus_dimensions": ["pricing"],
                }
            ]
        },
    )
    collected_at = datetime.now(timezone.utc)
    evidence_rows = [
        EvidenceRecord(
            id="ev_grounded",
            run_id=run.run_id,
            source_type="pricing_page",
            source_url="https://cursor.com/pricing",
            source_title="Cursor Pricing",
            quote="Cursor publishes pricing details.",
            sanitized_text="Cursor publishes pricing details.",
            span={
                "dimension": "pricing",
                "competitor_id": "comp_cursor",
                "source_authority": "official",
            },
            collected_by="step_researcher",
            collected_at=collected_at,
            desensitized=True,
        ),
        EvidenceRecord(
            id="ev_floor",
            run_id=run.run_id,
            source_type="article",
            source_url="https://example.com/copilot",
            source_title="Copilot mention",
            quote="placeholder",
            sanitized_text="placeholder",
            span={
                "dimension": "pricing",
                "competitor_id": "comp_copilot",
                "evidence_floor": True,
                "evidence_floor_reason": "competitor_grounding_miss",
            },
            collected_by="step_researcher",
            collected_at=collected_at,
            desensitized=True,
        ),
    ]

    snapshot = build_run_metrics_snapshot(
        run=run,
        evidence_rows=evidence_rows,
        step_rows=[],
        llm_rows=[],
        decision_rows=[],
        candidate_rows=[],
        comparison_rows=[],
    )

    assert snapshot.evidence_count_total == 2
    assert snapshot.evidence_floor_count == 1
    assert snapshot.non_floor_grounded_count == 1
