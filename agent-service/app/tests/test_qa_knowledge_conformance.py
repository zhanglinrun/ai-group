from __future__ import annotations

from service.qa.engine import build_qa_outcome
from service.qa.rules import RuleResult, rule_knowledge_schema_conformance
from schemas.qa import Rejection


def test_knowledge_schema_conformance_rejects_malformed_complete_claims() -> None:
    result = rule_knowledge_schema_conformance(
        knowledge={
            "schema_version": "schema_v0.2",
            "features": [
                {
                    "id": "feat_1",
                    "competitor_id": "Cursor",
                    "name": "Repo context",
                    "evidence_ids": [],
                }
            ],
            "pricings": [],
            "personas": [
                {
                    "id": "persona_1",
                    "name": "Buyer",
                    "role": "",
                    "pain_points": [],
                    "jobs_to_be_done": [],
                }
            ],
            "coverage": {"Cursor": {"feature": "complete", "pricing": "complete"}},
        },
        expected_competitors=["Cursor"],
    )

    assert result.passed is False
    assert result.severity == "blocking"
    assert result.reject_to == "analyst"
    assert "feature missing evidence_ids" in result.message
    assert "pricing missing" in result.message
    assert "persona missing" in result.message


def test_knowledge_schema_conformance_routes_no_evidence_to_researcher() -> None:
    result = rule_knowledge_schema_conformance(
        knowledge={
            "schema_version": "schema_v0.2",
            "features": [],
            "pricings": [],
            "personas": [],
            "coverage": {
                "Cursor": {
                    "feature": "insufficient_data",
                    "pricing": "insufficient_data",
                }
            },
        },
        expected_competitors=["Cursor"],
    )

    assert result.passed is False
    assert result.severity == "blocking"
    assert result.reject_to == "researcher"
    assert "[no_evidence]" in result.message


def test_knowledge_schema_conformance_routes_insufficient_evidence_to_researcher() -> None:
    result = rule_knowledge_schema_conformance(
        knowledge=_empty_schema_with_honest_coverage(),
        expected_competitors=["Cursor"],
        evidence_item_count=4,
        min_evidence_for_schema_floor=12,
    )

    assert result.passed is False
    assert result.severity == "blocking"
    assert result.reject_to == "researcher"
    assert "[insufficient_evidence]" in result.message


def _empty_schema_with_honest_coverage() -> dict[str, object]:
    return {
        "schema_version": "schema_v0.2",
        "features": [],
        "pricings": [],
        "personas": [],
        "coverage": {
            "Cursor": {
                "feature": "insufficient_data",
                "pricing": "insufficient_data",
            }
        },
    }


def test_knowledge_schema_conformance_rejects_empty_schema_with_sufficient_evidence() -> None:
    result = rule_knowledge_schema_conformance(
        knowledge=_empty_schema_with_honest_coverage(),
        expected_competitors=["Cursor"],
        evidence_item_count=84,
    )

    assert result.passed is False
    assert result.severity == "blocking"
    assert result.reject_to == "analyst"
    assert "[extraction_empty]" in result.message


def test_knowledge_schema_conformance_downgrades_empty_schema_floor_after_retry() -> None:
    # Trend/opportunity queries yield topical evidence that genuinely lacks
    # per-competitor schema. After one forced analyst retry, the empty-schema
    # floor must downgrade to a warning so the run finalizes instead of looping
    # into `degraded`.
    result = rule_knowledge_schema_conformance(
        knowledge=_empty_schema_with_honest_coverage(),
        expected_competitors=["Cursor"],
        evidence_item_count=84,
        qa_rejection_count=1,
    )

    assert result.passed is False
    assert result.severity == "warning"
    assert "[extraction_empty_retry]" in result.message


def test_knowledge_schema_conformance_landscape_skips_competitor_schema_floor() -> None:
    # A landscape (opportunity/trend) run has no apples-to-apples competitor set;
    # an empty per-competitor schema with honest coverage must pass on the FIRST
    # round (no forced retry, no degraded loop).
    result = rule_knowledge_schema_conformance(
        knowledge=_empty_schema_with_honest_coverage(),
        expected_competitors=["DeepSeek", "秒哒"],
        evidence_item_count=84,
        qa_rejection_count=0,
        require_competitor_schema=False,
    )

    assert result.passed is True


def test_knowledge_schema_conformance_landscape_still_blocks_malformed_items() -> None:
    # Even in landscape mode, if the analyst DID emit a malformed row it must block.
    result = rule_knowledge_schema_conformance(
        knowledge={
            "schema_version": "schema_v0.2",
            "features": [
                {
                    "id": "feat_1",
                    "competitor_id": "DeepSeek",
                    "name": "API",
                    "evidence_ids": [],
                }
            ],
            "pricings": [],
            "personas": [],
            "coverage": {},
        },
        expected_competitors=["DeepSeek"],
        evidence_item_count=84,
        require_competitor_schema=False,
    )

    assert result.passed is False
    assert result.severity == "blocking"
    assert "feature missing evidence_ids" in result.message


def test_knowledge_schema_conformance_malformed_still_blocks_after_retry() -> None:
    # Integrity failures (malformed rows) are NOT retry-aware — they remain
    # blocking regardless of rejection count.
    result = rule_knowledge_schema_conformance(
        knowledge={
            "schema_version": "schema_v0.2",
            "features": [
                {
                    "id": "feat_1",
                    "competitor_id": "Cursor",
                    "name": "Repo context",
                    "evidence_ids": [],
                }
            ],
            "pricings": [],
            "personas": [],
            "coverage": {"Cursor": {"feature": "complete", "pricing": "insufficient_data"}},
        },
        expected_competitors=["Cursor"],
        evidence_item_count=84,
        qa_rejection_count=3,
    )

    assert result.passed is False
    assert result.severity == "blocking"
    assert "feature missing evidence_ids" in result.message


def test_knowledge_schema_conformance_passes_complete_minimum_schema() -> None:
    result = rule_knowledge_schema_conformance(
        knowledge={
            "schema_version": "schema_v0.2",
            "features": [
                {
                    "id": f"feat_{index}",
                    "competitor_id": "Cursor",
                    "name": f"Feature {index}",
                    "evidence_ids": [f"ev_{index}"],
                }
                for index in range(3)
            ],
            "pricings": [
                {
                    "id": "price_1",
                    "competitor_id": "Cursor",
                    "model": "seat",
                    "evidence_ids": ["ev_price"],
                }
            ],
            "personas": [
                {
                    "id": "persona_1",
                    "name": "Engineering manager",
                    "role": "engineering_manager",
                    "pain_points": ["Manual review load"],
                    "jobs_to_be_done": [],
                }
            ],
            "coverage": {"Cursor": {"feature": "complete", "pricing": "complete"}},
        },
        expected_competitors=["Cursor"],
    )

    assert result.passed is True


def test_knowledge_schema_conformance_routes_blocking_failure_to_analyst() -> None:
    rule_results: list[RuleResult] = [
        rule_knowledge_schema_conformance(
            knowledge={
                "schema_version": "schema_v0.2",
                "features": [],
                "pricings": [],
                "personas": [],
                "coverage": {},
            },
            expected_competitors=["Cursor"],
        )
    ]

    result = build_qa_outcome(
        target_step_id="step_writer_001",
        reviewer_step_id="step_qa_001",
        rule_results=rule_results,
        qa_rejection_count=0,
    )

    assert isinstance(result, Rejection)
    assert result.reject_to == "researcher"
    assert result.failed_rule_ids == ["rule_knowledge_schema_conformance"]
