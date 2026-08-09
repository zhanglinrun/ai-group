from __future__ import annotations

from datetime import datetime, timezone

from models.evidence import EvidenceRecord
from service.qa.numeric_claims import extract_numeric_claim_candidates


def _evidence(evidence_id: str, text: str) -> EvidenceRecord:
    return EvidenceRecord(
        id=evidence_id,
        run_id="run_numeric_claims",
        source_type="official_site",
        source_url="https://example.com",
        source_title="Example",
        quote=text,
        sanitized_text=text,
        span={"dimension": "pricing", "competitor_id": "comp_a"},
        collected_by="step_researcher",
        collected_at=datetime.now(timezone.utc),
        desensitized=True,
    )


def test_extract_numeric_claim_candidates_pairs_section_evidence() -> None:
    report_json = {
        "sections": [
            {
                "section_id": "efficiency",
                "content_markdown": (
                    "部署后邮件跟进效率提升 28%。\n"
                    "线下拜访复盘从 1.5 小时缩短到 30分钟。"
                ),
                "evidence_refs": ["ev_efficiency"],
            },
            {
                "section_id": "pricing",
                "content_markdown": "价格区间约 2.7万-3.2万美元，适合预算充足团队。",
                "evidence_refs": ["ev_pricing"],
            },
        ]
    }

    candidates = extract_numeric_claim_candidates(
        report_json=report_json,
        evidence_items=[
            _evidence("ev_efficiency", "Customer case mentions a 28% efficiency lift."),
            _evidence("ev_pricing", "Pricing ranges from $27k to $32k."),
        ],
    )

    assert [item.section_id for item in candidates] == ["efficiency", "efficiency", "pricing"]
    assert candidates[0].numbers == ["28%"]
    assert candidates[1].numbers == ["1.5 小时", "30分钟"]
    assert candidates[2].numbers == ["2.7万-3.2万美元"]
    assert candidates[0].evidence_ids == ["ev_efficiency"]
    assert candidates[0].evidence_quotes == [
        {
            "evidence_id": "ev_efficiency",
            "quote_preview": "Customer case mentions a 28% efficiency lift.",
        }
    ]
