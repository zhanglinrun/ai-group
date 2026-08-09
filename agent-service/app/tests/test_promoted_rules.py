from __future__ import annotations

from datetime import datetime, timedelta, timezone

from models.evidence import EvidenceRecord
from service.qa.promoted_rules import evaluate_promoted_rule_yaml


def _make_evidence(
    *,
    evidence_id: str,
    source_type: str,
    collected_days_ago: int,
) -> EvidenceRecord:
    now = datetime.now(timezone.utc)
    return EvidenceRecord(
        id=evidence_id,
        run_id="run_promoted_rules_001",
        source_type=source_type,
        source_url="https://example.com/source",
        source_title="Source",
        quote="quoted",
        sanitized_text="sanitized",
        span={"dimension": "pricing", "competitor_id": "comp_cursor"},
        collected_by="step_researcher_001",
        collected_at=now - timedelta(days=collected_days_ago),
        desensitized=True,
    )


def _pricing_report(*, refs: list[str], content_len: int = 120) -> dict[str, object]:
    return {
        "template_id": "default",
        "sections": [
            {
                "section_id": "pricing",
                "title": "Pricing Overview",
                "content_markdown": "x" * content_len,
                "evidence_refs": refs,
            }
        ],
    }


def test_promoted_rule_section_id_in_matches_chinese_title() -> None:
    rule_yaml = """
id: rule_pricing_requires_tier
when:
  section_id_in: ["pricing"]
require:
  evidence_refs_count_gte: 1
  section_content_min_chars: 80
severity: blocking
reject_to: writer
message: "Pricing section must include concrete tier details."
"""
    report = {
        "template_id": "default",
        "sections": [
            {
                "section_id": "pricing",
                "title": "定价模型拆解",
                "content_markdown": "x" * 120,
                "evidence_refs": [],
            }
        ],
    }

    evaluated = evaluate_promoted_rule_yaml(
        promoted_rule_id="rule_promoted_rule_pricing_requires_tier",
        rule_yaml=rule_yaml,
        content_json=report,
        evidence_by_id={},
        now=datetime.now(timezone.utc),
    )

    assert evaluated.parse_error is None
    assert evaluated.result.passed is False
    assert "evidence_refs_count=0 < 1" in evaluated.result.message


def test_promoted_rule_passes_when_all_requirements_met() -> None:
    rule_yaml = """
id: rule_pricing_requires_recent_source
when:
  section_title_contains: ["pricing"]
require:
  has_evidence_with:
    source_type_in: ["pricing_page"]
    collected_within_days: 90
  evidence_refs_count_gte: 1
  section_content_min_chars: 80
severity: blocking
reject_to: writer
message: "Pricing section must cite recent official pricing evidence."
"""
    evidence = _make_evidence(
        evidence_id="ev_1",
        source_type="pricing_page",
        collected_days_ago=7,
    )
    evaluated = evaluate_promoted_rule_yaml(
        promoted_rule_id="rule_promoted_rule_pricing_requires_recent_source",
        rule_yaml=rule_yaml,
        content_json=_pricing_report(refs=["ev_1"], content_len=120),
        evidence_by_id={evidence.id: evidence},
        now=datetime.now(timezone.utc),
    )
    assert evaluated.parse_error is None
    assert evaluated.enforced is True
    assert evaluated.result.passed is True
    assert evaluated.result.severity == "blocking"


def test_promoted_rule_blocks_when_recent_official_source_missing() -> None:
    rule_yaml = """
id: rule_pricing_requires_recent_source
when:
  section_title_contains: ["pricing"]
require:
  has_evidence_with:
    source_type_in: ["pricing_page", "official_doc"]
    collected_within_days: 30
severity: blocking
reject_to: writer
message: "Pricing section must cite recent official evidence."
"""
    evidence = _make_evidence(
        evidence_id="ev_2",
        source_type="blog",
        collected_days_ago=120,
    )
    evaluated = evaluate_promoted_rule_yaml(
        promoted_rule_id="rule_promoted_rule_pricing_requires_recent_source",
        rule_yaml=rule_yaml,
        content_json=_pricing_report(refs=["ev_2"], content_len=140),
        evidence_by_id={evidence.id: evidence},
        now=datetime.now(timezone.utc),
    )
    assert evaluated.parse_error is None
    assert evaluated.result.passed is False
    assert evaluated.result.reject_to == "writer"
    assert "missing qualified evidence match" in evaluated.result.message


def test_promoted_rule_not_triggered_when_target_section_absent() -> None:
    rule_yaml = """
id: rule_pricing_requires_recent_source
when:
  section_title_contains: ["pricing"]
require:
  section_content_min_chars: 80
severity: blocking
reject_to: writer
"""
    report = {
        "sections": [
            {
                "section_id": "feature",
                "title": "Feature Overview",
                "content_markdown": "y" * 150,
                "evidence_refs": [],
            }
        ]
    }
    evaluated = evaluate_promoted_rule_yaml(
        promoted_rule_id="rule_promoted_rule_pricing_requires_recent_source",
        rule_yaml=rule_yaml,
        content_json=report,
        evidence_by_id={},
        now=datetime.now(timezone.utc),
    )
    assert evaluated.parse_error is None
    assert evaluated.result.passed is True
    assert "not triggered" in evaluated.result.message


def test_promoted_rule_parse_error_blocks_report() -> None:
    evaluated = evaluate_promoted_rule_yaml(
        promoted_rule_id="rule_promoted_rule_pricing_requires_recent_source",
        rule_yaml="id: [invalid",
        content_json=_pricing_report(refs=[]),
        evidence_by_id={},
        now=datetime.now(timezone.utc),
    )
    assert evaluated.enforced is False
    assert evaluated.parse_error is not None
    assert evaluated.result.passed is False
    assert evaluated.result.severity == "blocking"
    assert "parse_error:" in evaluated.result.message


def test_promoted_rule_fails_on_evidence_count_and_content_length() -> None:
    rule_yaml = """
id: rule_pricing_requires_density
require:
  evidence_refs_count_gte: 2
  section_content_min_chars: 100
severity: blocking
reject_to: writer
"""
    evidence = _make_evidence(
        evidence_id="ev_3",
        source_type="official_doc",
        collected_days_ago=2,
    )
    evaluated = evaluate_promoted_rule_yaml(
        promoted_rule_id="rule_promoted_rule_pricing_requires_density",
        rule_yaml=rule_yaml,
        content_json=_pricing_report(refs=["ev_3"], content_len=40),
        evidence_by_id={evidence.id: evidence},
        now=datetime.now(timezone.utc),
    )
    assert evaluated.parse_error is None
    assert evaluated.result.passed is False
    assert "evidence_refs_count=1 < 2" in evaluated.result.message
    assert "content_len=40 < 100" in evaluated.result.message

