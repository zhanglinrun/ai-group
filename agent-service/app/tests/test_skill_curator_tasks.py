from __future__ import annotations

import pytest

from service.event_bus import RunEventType
from service.metrics import RunMetricsSnapshot
from service.skill_curator import tasks as curator_tasks


def _snapshot(
    *,
    coverage_rate: float = 1.0,
    dimension_coverage_rate: float = 0.5,
    evidence_dimension_coverage_rate: float = 0.5,
    report_section_coverage_rate: float = 1.0,
    qa_rejection_rate: float = 0.0,
) -> RunMetricsSnapshot:
    return RunMetricsSnapshot(
        run_id="run_curator_gate",
        coverage_rate=coverage_rate,
        evidence_count_total=3,
        evidence_count_by_competitor={"comp_cursor": 3},
        evidence_count_by_dimension={"pricing": 1, "feature": 1},
        comparison_dimensions=["pricing"],
        conclusion_sections=[],
        report_section_ids=["feature"],
        dimension_coverage_rate=dimension_coverage_rate,
        evidence_dimension_coverage_rate=evidence_dimension_coverage_rate,
        report_char_count=3200,
        report_section_count=3,
        report_depth="deep",
        report_section_coverage_rate=report_section_coverage_rate,
        knowledge_feature_count=3,
        knowledge_pricing_count=1,
        knowledge_persona_count=1,
        knowledge_schema_coverage_rate=0.8,
        source_type_distribution={"pricing_page": 1, "docs": 2},
        source_authority_distribution={"official": 1, "third_party": 2},
        locale_match_rate=1.0,
        locale_distribution={"global:en": 3},
        desensitization_coverage=1.0,
        qa_total_steps=1,
        qa_rejected_steps=0,
        qa_rejection_rate=qa_rejection_rate,
        supervisor_iterations=3,
        llm_token_total=1000,
        llm_call_count=4,
        llm_latency_p50_ms=100,
        llm_provider_error_count=0,
        llm_retry_total=0,
        manual_review_rate=0.0,
        manual_review_is_proxy=True,
        run_wall_clock_seconds=60,
    )


def test_curator_skip_reason_uses_quality_thresholds(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(curator_tasks.settings, "CURATOR_MIN_COVERAGE_RATE", 1.0)
    monkeypatch.setattr(curator_tasks.settings, "CURATOR_MIN_DIMENSION_COVERAGE_RATE", 0.5)
    monkeypatch.setattr(
        curator_tasks.settings,
        "CURATOR_MIN_REPORT_SECTION_COVERAGE_RATE",
        1.0,
    )
    monkeypatch.setattr(curator_tasks.settings, "CURATOR_MAX_QA_REJECTION_RATE", 0.5)

    assert (
        curator_tasks._curator_skip_reason(  # noqa: SLF001 - focused gate regression
            run_status="degraded",
            snapshot=_snapshot(),
        )
        == "run_degraded"
    )
    assert (
        curator_tasks._curator_skip_reason(  # noqa: SLF001 - focused gate regression
            run_status="completed",
            snapshot=_snapshot(evidence_dimension_coverage_rate=0.49),
        )
        == "evidence_dimension_coverage_rate_below_threshold"
    )
    # The downstream dimension_coverage_rate must NOT gate the curator anymore:
    # a report-section-only coverage of 1.0 with poor evidence must still skip.
    assert (
        curator_tasks._curator_skip_reason(  # noqa: SLF001 - focused gate regression
            run_status="completed",
            snapshot=_snapshot(
                dimension_coverage_rate=1.0,
                evidence_dimension_coverage_rate=0.49,
            ),
        )
        == "evidence_dimension_coverage_rate_below_threshold"
    )
    assert (
        curator_tasks._curator_skip_reason(  # noqa: SLF001 - focused gate regression
            run_status="completed",
            snapshot=_snapshot(),
        )
        is None
    )


@pytest.mark.asyncio
async def test_run_skill_curator_skips_low_quality_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[tuple[str, dict[str, object]]] = []

    async def fake_emit_run_event(
        *,
        run_id: str,
        event_type: RunEventType,
        payload: dict[str, object] | None = None,
        step_id: str | None = None,
    ) -> None:
        del run_id, step_id
        events.append((event_type.value, payload or {}))

    async def fake_load_decision(run_id: str) -> tuple[str | None, dict[str, object]]:
        del run_id
        return (
            "dimension_coverage_rate_below_threshold",
            {
                "run_status": "completed",
                "reason": "dimension_coverage_rate_below_threshold",
                "dimension_coverage_rate": 0.0,
            },
        )

    async def fail_generate(**_: object) -> object:
        raise AssertionError("curator generation should not run for skipped samples")

    monkeypatch.setattr(curator_tasks, "emit_run_event", fake_emit_run_event)
    monkeypatch.setattr(curator_tasks, "_load_curator_skip_decision", fake_load_decision)
    monkeypatch.setattr(curator_tasks, "generate_skill_candidates", fail_generate)

    await curator_tasks.run_skill_curator_for_run(
        run_id="run_low_quality",
        domain_hint="ai coding assistants",
    )

    assert [event_type for event_type, _ in events] == [
        RunEventType.CURATOR_START.value,
        RunEventType.CURATOR_SKIPPED.value,
        RunEventType.CURATOR_FINISH.value,
    ]
    assert events[1][1]["reason"] == "dimension_coverage_rate_below_threshold"
    assert events[2][1]["status"] == "skipped"
