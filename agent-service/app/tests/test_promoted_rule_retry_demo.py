from __future__ import annotations

from datetime import datetime, timezone

from models.evidence import EvidenceRecord
from service.qa.promoted_rules import evaluate_promoted_rule_yaml


def test_promoted_rule_retry_demo_blocks_on_short_content() -> None:
    evidence = EvidenceRecord(
        id="ev_retry_demo",
        run_id="run_retry_demo",
        source_type="official_doc",
        source_url="https://example.com",
        source_title="Example",
        quote="quoted",
        sanitized_text="sanitized",
        span={"dimension": "feature", "competitor_id": "comp_cursor"},
        collected_by="step_researcher_001",
        collected_at=datetime.now(timezone.utc),
        desensitized=True,
    )
    result = evaluate_promoted_rule_yaml(
        promoted_rule_id="rule_promoted_rule_pricing_retry_demo",
        rule_yaml=(
            "id: rule_pricing_retry_demo\n"
            "require:\n"
            "  section_content_min_chars: 120\n"
            "severity: blocking\n"
            "reject_to: writer\n"
            "message: \"Writer must provide sufficient section detail.\"\n"
        ),
        content_json={
            "sections": [
                {
                    "title": "Pricing",
                    "content_markdown": "retry demo short content",
                    "evidence_refs": [evidence.id],
                }
            ]
        },
        evidence_by_id={evidence.id: evidence},
        now=datetime.now(timezone.utc),
    )
    assert result.result.passed is False
    assert "content_len=24 < 120" in result.result.message

